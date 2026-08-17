package com.estoma.academic.messaging;

import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.observability.AcademicMessagingMetrics;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Minimal foreign facts. Every projection is versioned by its source aggregate, never a local FK. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class ExternalProjectionListener {
  private static final Logger log = LoggerFactory.getLogger(ExternalProjectionListener.class);

  private final EnvelopeCodec codec;
  private final JdbcClient jdbc;
  private final RetryPublisher retryPublisher;
  private final AcademicMessagingMetrics metrics;
  private final TransactionTemplate transactions;

  ExternalProjectionListener(
      EnvelopeCodec codec,
      JdbcClient jdbc,
      RetryPublisher retryPublisher,
      AcademicMessagingMetrics metrics,
      PlatformTransactionManager transactionManager) {
    this.codec = codec;
    this.jdbc = jdbc;
    this.retryPublisher = retryPublisher;
    this.metrics = metrics;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @RabbitListener(id = "academicEventProjectionListener", queues = AcademicTopology.EVENT_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      Boolean applied = transactions.execute(status -> apply(codec.decode(message.getBody())));
      if (!Boolean.TRUE.equals(applied)) {
        metrics.duplicate();
      }
      metrics.consumed();
      channel.basicAck(tag, false);
    } catch (IllegalArgumentException invalid) {
      log.warn("invalid academic external event messageId={} error={}",
          message.getMessageProperties().getMessageId(), invalid.toString());
      if (retryPublisher.deadLetter(message, "academic.events")) {
        metrics.dlq();
        channel.basicAck(tag, false);
      } else {
        channel.basicNack(tag, false, true);
      }
    } catch (RuntimeException ex) {
      log.warn("academic external event consumption failed messageId={} error={}",
          message.getMessageProperties().getMessageId(), ex.toString());
      RetryPublisher.RetryOutcome outcome = retryPublisher.retry(message, "academic.events.retry");
      if (outcome == RetryPublisher.RetryOutcome.PUBLISHED) {
        metrics.retry();
        channel.basicAck(tag, false);
      } else if (outcome == RetryPublisher.RetryOutcome.EXHAUSTED) {
        metrics.dlq();
        channel.basicNack(tag, false, false);
      } else {
        channel.basicNack(tag, false, true);
      }
    }
  }

  boolean apply(MessageEnvelope e) {
    ExternalEventContract.validate(e);
    validatePayload(e);
    Instant now = Instant.now();
    if (jdbc.sql("""
        INSERT INTO processed_messages (
          consumer_name,message_id,correlation_id,message_type,message_kind,producer,
          aggregate_id,aggregate_version,envelope,processing_state,reconciliation_state,processed_at)
        VALUES ('academic.events',:id,:correlation,:type,:kind,:producer,:aggregate,:version,
          CAST(:envelope AS jsonb),'APPLIED',NULL,:now)
        ON CONFLICT DO NOTHING
        """)
        .param("id", e.messageId())
        .param("correlation", e.correlationId())
        .param("type", e.messageType())
        .param("kind", e.messageKind())
        .param("producer", e.producer())
        .param("aggregate", e.aggregateId())
        .param("version", e.aggregateVersion())
        .param("envelope", codec.json(e))
        .param("now", java.sql.Timestamp.from(now))
        .update() == 0) {
      return false;
    }

    JsonNode p = e.payload();
    int version = e.aggregateVersion();
    switch (e.messageType()) {
      case "PersonCreated", "PersonActivated", "PersonDeactivated" ->
          upsertPerson(requiredUuid(p, "personId"), status(p), version);
      case "PersonUpdated" ->
          updatePerson(requiredUuid(p, "personId"), status(p), version);
      case "AccountCreated" ->
          upsertAccount(requiredUuid(p, "accountId"), requiredUuid(p, "personId"),
              requiredText(p, "loginType"), status(p), null, version);
      case "AccountActivated" ->
          upsertAccount(requiredUuid(p, "accountId"), requiredUuid(p, "personId"),
              text(p, "loginType"), status(p), null, version);
      case "AccountDeactivated" ->
          upsertAccount(requiredUuid(p, "accountId"), requiredUuid(p, "personId"),
              text(p, "loginType"), status(p), requiredText(p, "reasonCode"), version);
      case "AccountRoleAssigned" -> {
        UUID accountId = requiredUuid(p, "accountId");
        if (upsertAuthorization(accountId, requiredText(p, "roleCode"), "ACTIVE", version) > 0) {
          clearDegradedAccount(accountId);
        }
      }
      case "AccountRoleChanged" ->
          changeAuthorization(requiredUuid(p, "accountId"),
              requiredText(p, "previousRoleCode"), requiredText(p, "newRoleCode"), version);
      case "SystemAccessGranted", "SystemAccessRestored" ->
          upsertSystemAccess(requiredUuid(p, "accountId"), requiredUuid(p, "systemAccessId"),
              requiredUuid(p, "systemId"), requiredText(p, "systemCode"), "ACTIVE", version);
      case "SystemAccessSuspended" ->
          upsertSystemAccess(requiredUuid(p, "accountId"), requiredUuid(p, "systemAccessId"),
              requiredUuid(p, "systemId"), requiredText(p, "systemCode"), "SUSPENDED", version);
      case "SystemAccessRevoked" ->
          upsertSystemAccess(requiredUuid(p, "accountId"), requiredUuid(p, "systemAccessId"),
              requiredUuid(p, "systemId"), requiredText(p, "systemCode"), "REVOKED", version);
      default -> throw new IllegalArgumentException("unsupported external event " + e.messageType());
    }
    return true;
  }

  private void upsertPerson(UUID id, String status, int version) {
    if (id != null && status != null) {
      jdbc.sql("""
          INSERT INTO person_status_projection (person_id,status,source_version,updated_at)
          VALUES (:id,:status,:version,now())
          ON CONFLICT (person_id) DO UPDATE SET
            status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at
          WHERE person_status_projection.source_version < excluded.source_version
          """)
          .param("id", id).param("status", status).param("version", version).update();
    }
  }

  private void updatePerson(UUID id, String status, int version) {
    if (status != null) {
      upsertPerson(id, status, version);
    } else if (id != null) {
      jdbc.sql("""
          UPDATE person_status_projection SET source_version=:version,updated_at=now()
          WHERE person_id=:id AND source_version < :version
          """).param("id", id).param("version", version).update();
    }
  }

  private void upsertAccount(
      UUID id, UUID person, String login, String status, String deactivationReasonCode, int version) {
    if (id == null || status == null) {
      return;
    }
    jdbc.sql("""
        INSERT INTO account_status_projection (
          account_id,person_id,login_type,status,source_version,last_deactivation_reason_code,updated_at)
        VALUES (:id,:person,:login,:status,:version,:reason,now())
        ON CONFLICT (account_id) DO UPDATE SET
          person_id=COALESCE(account_status_projection.person_id,excluded.person_id),
          login_type=COALESCE(account_status_projection.login_type,excluded.login_type),
          status=CASE WHEN account_status_projection.source_version < excluded.source_version
                      THEN excluded.status ELSE account_status_projection.status END,
          last_deactivation_reason_code=CASE WHEN account_status_projection.source_version < excluded.source_version
                      THEN excluded.last_deactivation_reason_code ELSE account_status_projection.last_deactivation_reason_code END,
          source_version=GREATEST(account_status_projection.source_version,excluded.source_version),
          updated_at=CASE WHEN account_status_projection.source_version < excluded.source_version
                          OR (account_status_projection.person_id IS NULL AND excluded.person_id IS NOT NULL)
                          OR (account_status_projection.login_type IS NULL AND excluded.login_type IS NOT NULL)
                          THEN now() ELSE account_status_projection.updated_at END
        """)
        .param("id", id).param("person", person).param("login", login).param("status", status)
        .param("reason", deactivationReasonCode).param("version", version).update();
  }

  private int upsertAuthorization(UUID id, String role, String status, int version) {
    return id != null && role != null
        ? jdbc.sql("""
            INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at)
            VALUES (:id,:role,:status,:version,now())
            ON CONFLICT (account_id,role_code) DO UPDATE SET
              status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at
            WHERE authorization_context_projection.source_version < excluded.source_version
            """)
            .param("id", id).param("role", role).param("status", status).param("version", version).update()
        : 0;
  }

  private void changeAuthorization(UUID id, String oldRole, String newRole, int version) {
    int changed = 0;
    if (oldRole != null && !oldRole.equals(newRole)) {
      changed += upsertAuthorization(id, oldRole, "INACTIVE", version);
    }
    changed += upsertAuthorization(id, newRole, "ACTIVE", version);
    if (changed > 0 && id != null) {
      clearDegradedAccount(id);
    }
  }

  private void upsertSystemAccess(
      UUID id, UUID systemAccessId, UUID systemId, String system, String status, int version) {
    if (id != null && system != null) {
      jdbc.sql("""
          INSERT INTO system_access_context_projection
            (account_id,system_access_id,system_id,system_code,status,source_version,updated_at)
          VALUES (:id,:systemAccessId,:systemId,:system,:status,:version,now())
          ON CONFLICT (account_id,system_code) DO UPDATE SET
            system_access_id=COALESCE(excluded.system_access_id,system_access_context_projection.system_access_id),
            system_id=COALESCE(excluded.system_id,system_access_context_projection.system_id),
            status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at
          WHERE system_access_context_projection.source_version < excluded.source_version
          """)
          .param("id", id).param("systemAccessId", systemAccessId).param("systemId", systemId)
          .param("system", system).param("status", status).param("version", version).update();
    }
  }

  private void clearDegradedAccount(UUID accountId) {
    jdbc.sql("DELETE FROM authorization_projection_degraded_accounts WHERE account_id=:id")
        .param("id", accountId).update();
  }

  private static void validatePayload(MessageEnvelope e) {
    JsonNode p = e.payload();
    if (p == null || p.isNull() || !p.isObject()) {
      throw new IllegalArgumentException("external event payload must be an object");
    }
    switch (e.messageType()) {
      case "PersonCreated", "PersonActivated" -> {
        requireAggregateMatches(e, requiredUuid(p, "personId"), "personId");
        requiredStatus(p, "ACTIVE");
      }
      case "PersonDeactivated" -> {
        requireAggregateMatches(e, requiredUuid(p, "personId"), "personId");
        requiredStatus(p, "INACTIVE");
      }
      case "PersonUpdated" -> requireAggregateMatches(e, requiredUuid(p, "personId"), "personId");
      case "AccountCreated" -> {
        requireAggregateMatches(e, requiredUuid(p, "accountId"), "accountId");
        requiredUuid(p, "personId");
        requiredLoginType(p);
        requiredStatus(p, "INACTIVE");
      }
      case "AccountActivated" -> {
        requireAggregateMatches(e, requiredUuid(p, "accountId"), "accountId");
        requiredUuid(p, "personId");
        requiredStatus(p, "ACTIVE");
      }
      case "AccountDeactivated" -> {
        requireAggregateMatches(e, requiredUuid(p, "accountId"), "accountId");
        requiredUuid(p, "personId");
        requiredStatus(p, "INACTIVE");
        requiredText(p, "reasonCode");
      }
      case "AccountRoleAssigned" -> {
        requireAggregateMatches(e, requiredUuid(p, "accountId"), "accountId");
        requiredUuid(p, "roleId");
        requiredText(p, "roleCode");
      }
      case "AccountRoleChanged" -> {
        requireAggregateMatches(e, requiredUuid(p, "accountId"), "accountId");
        requiredUuid(p, "previousRoleId");
        requiredText(p, "previousRoleCode");
        requiredUuid(p, "newRoleId");
        requiredText(p, "newRoleCode");
      }
      case "SystemAccessGranted" -> {
        requireAggregateMatches(e, requiredUuid(p, "systemAccessId"), "systemAccessId");
        requiredUuid(p, "accountId");
        requiredUuid(p, "systemId");
        requiredText(p, "systemCode");
        requiredUuid(p, "roleId");
        requiredText(p, "roleCode");
        requiredStatus(p, "ACTIVE");
      }
      case "SystemAccessRestored" -> {
        requireAggregateMatches(e, requiredUuid(p, "systemAccessId"), "systemAccessId");
        requiredUuid(p, "accountId");
        requiredUuid(p, "systemId");
        requiredText(p, "systemCode");
        requiredStatus(p, "ACTIVE");
      }
      case "SystemAccessSuspended" -> {
        requireAggregateMatches(e, requiredUuid(p, "systemAccessId"), "systemAccessId");
        requiredUuid(p, "accountId");
        requiredUuid(p, "systemId");
        requiredText(p, "systemCode");
        requiredStatus(p, "SUSPENDED");
      }
      case "SystemAccessRevoked" -> {
        requireAggregateMatches(e, requiredUuid(p, "systemAccessId"), "systemAccessId");
        requiredUuid(p, "accountId");
        requiredUuid(p, "systemId");
        requiredText(p, "systemCode");
        requiredStatus(p, "REVOKED");
      }
      default -> throw new IllegalArgumentException("unsupported external event " + e.messageType());
    }
  }

  private static void requireAggregateMatches(MessageEnvelope envelope, UUID payloadId, String field) {
    if (!envelope.aggregateId().equals(payloadId)) {
      throw new IllegalArgumentException("aggregateId does not match payload." + field);
    }
  }

  private static UUID requiredUuid(JsonNode p, String field) {
    try {
      return p.hasNonNull(field) ? UUID.fromString(p.path(field).asString()) : missing(field);
    } catch (RuntimeException ex) {
      throw new IllegalArgumentException("invalid " + field, ex);
    }
  }

  private static String requiredText(JsonNode p, String field) {
    String value = text(p, field);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("missing " + field);
    }
    return value;
  }

  private static String requiredStatus(JsonNode p, String expected) {
    String actual = requiredText(p, "status");
    if (!expected.equals(actual)) {
      throw new IllegalArgumentException("invalid status");
    }
    return actual;
  }

  private static String requiredLoginType(JsonNode p) {
    String value = requiredText(p, "loginType");
    if (!"MATRICULA".equals(value) && !"USERNAME".equals(value)) {
      throw new IllegalArgumentException("invalid loginType");
    }
    return value;
  }

  private static UUID missing(String field) {
    throw new IllegalArgumentException("missing " + field);
  }

  private static String text(JsonNode p, String field) {
    return p.hasNonNull(field) ? p.path(field).asString() : null;
  }

  private static String status(JsonNode p) {
    return p.hasNonNull("status") ? p.path("status").asString()
        : p.hasNonNull("currentStatus") ? p.path("currentStatus").asString() : null;
  }
}
