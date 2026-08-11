package com.estoma.academic.messaging;

import com.estoma.academic.contract.MessageEnvelope;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Minimal foreign facts. Every projection is versioned by its source aggregate, never a local FK. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class ExternalProjectionListener {
  private final EnvelopeCodec codec; private final JdbcClient jdbc; private final RetryPublisher retryPublisher;
  ExternalProjectionListener(EnvelopeCodec codec, JdbcClient jdbc, RetryPublisher retryPublisher) { this.codec = codec; this.jdbc = jdbc; this.retryPublisher = retryPublisher; }

  @RabbitListener(id = "academicEventProjectionListener", queues = AcademicTopology.EVENT_QUEUE, containerFactory = "manualAckContainerFactory")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try { apply(codec.decode(message.getBody())); channel.basicAck(tag, false); }
    catch (RuntimeException ex) { if (retryPublisher.retry(message, "academic.events.retry")) channel.basicAck(tag, false); else channel.basicNack(tag, false, false); }
  }

  @Transactional
  void apply(MessageEnvelope e) {
    if (e.messageId() == null || e.messageVersion() != 1 || !"EVENT".equals(e.messageKind())) throw new IllegalArgumentException("invalid event envelope");
    Instant now = Instant.now();
    if (jdbc.sql("""
        INSERT INTO processed_messages (consumer_name,message_id,correlation_id,message_type,message_kind,producer,aggregate_id,aggregate_version,envelope,processing_state,reconciliation_state,processed_at)
        VALUES ('academic.events',:id,:correlation,:type,:kind,:producer,:aggregate,:version,CAST(:envelope AS jsonb),'APPLIED','EVENT_PENDING',:now)
        ON CONFLICT DO NOTHING
        """).param("id",e.messageId()).param("correlation",e.correlationId()).param("type",e.messageType()).param("kind",e.messageKind()).param("producer",e.producer()).param("aggregate",e.aggregateId()).param("version",e.aggregateVersion()).param("envelope",codec.json(e)).param("now",java.sql.Timestamp.from(now)).update() == 0) return;
    JsonNode p = e.payload(); int version = e.aggregateVersion() == null ? 0 : e.aggregateVersion();
    switch (e.messageType()) {
      case "PersonCreated", "PersonActivated", "PersonDeactivated" -> upsertPerson(uuid(p,"personId", e.aggregateId()), status(p), version);
      case "PersonUpdated" -> updatePerson(uuid(p,"personId", e.aggregateId()), status(p), version);
      case "AccountCreated", "AccountActivated", "AccountDeactivated" -> upsertAccount(uuid(p,"accountId",e.aggregateId()), uuid(p,"personId",null), text(p,"loginType"), status(p), version);
      case "AccountUpdated" -> updateAccount(uuid(p,"accountId",e.aggregateId()), uuid(p,"personId",null), text(p,"loginType"), status(p), version);
      case "AccountRoleAssigned" -> upsertAuthorization(uuid(p,"accountId",e.aggregateId()), requiredText(p,"roleCode"), "ACTIVE", version);
      case "AccountRoleChanged" -> changeAuthorization(uuid(p,"accountId",e.aggregateId()), text(p,"roleCode"), requiredText(p,"newRoleCode"), version);
      case "SystemAccessGranted", "SystemAccessRestored" -> upsertSystemAccess(uuid(p,"accountId",e.aggregateId()), requiredText(p,"systemCode"), "ACTIVE", version);
      case "SystemAccessSuspended" -> upsertSystemAccess(uuid(p,"accountId",e.aggregateId()), requiredText(p,"systemCode"), "SUSPENDED", version);
      case "SystemAccessRevoked" -> upsertSystemAccess(uuid(p,"accountId",e.aggregateId()), requiredText(p,"systemCode"), "REVOKED", version);
      default -> { }
    }
  }

  private void upsertPerson(UUID id, String status, int version) { if (id != null && status != null) jdbc.sql("INSERT INTO person_status_projection (person_id,status,source_version,updated_at) VALUES (:id,:status,:version,now()) ON CONFLICT (person_id) DO UPDATE SET status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at WHERE person_status_projection.source_version < excluded.source_version").param("id",id).param("status",status).param("version",version).update(); }
  private void updatePerson(UUID id, String status, int version) { if (status != null) upsertPerson(id,status,version); else if (id != null) jdbc.sql("UPDATE person_status_projection SET source_version=:version,updated_at=now() WHERE person_id=:id AND source_version < :version").param("id",id).param("version",version).update(); }
  private void upsertAccount(UUID id, UUID person, String login, String status, int version) { if (id != null && status != null) jdbc.sql("INSERT INTO account_status_projection (account_id,person_id,login_type,status,source_version,updated_at) VALUES (:id,:person,:login,:status,:version,now()) ON CONFLICT (account_id) DO UPDATE SET person_id=excluded.person_id,login_type=excluded.login_type,status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at WHERE account_status_projection.source_version < excluded.source_version").param("id",id).param("person",person).param("login",login).param("status",status).param("version",version).update(); }
  private void updateAccount(UUID id, UUID person, String login, String status, int version) { if (status != null) { upsertAccount(id,person,login,status,version); return; } if (id != null) jdbc.sql("UPDATE account_status_projection SET person_id=COALESCE(:person,person_id),login_type=COALESCE(:login,login_type),source_version=:version,updated_at=now() WHERE account_id=:id AND source_version < :version").param("id",id).param("person",person).param("login",login).param("version",version).update(); }
  private void upsertAuthorization(UUID id, String role, String status, int version) { if (id != null && role != null) jdbc.sql("INSERT INTO authorization_context_projection (account_id,role_code,status,source_version,updated_at) VALUES (:id,:role,:status,:version,now()) ON CONFLICT (account_id,role_code) DO UPDATE SET status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at WHERE authorization_context_projection.source_version < excluded.source_version").param("id",id).param("role",role).param("status",status).param("version",version).update(); }
  private void changeAuthorization(UUID id, String oldRole, String newRole, int version) { if (oldRole != null && !oldRole.equals(newRole)) upsertAuthorization(id,oldRole,"INACTIVE",version); upsertAuthorization(id,newRole,"ACTIVE",version); }
  private void upsertSystemAccess(UUID id, String system, String status, int version) { if (id != null && system != null) jdbc.sql("INSERT INTO system_access_context_projection (account_id,system_code,status,source_version,updated_at) VALUES (:id,:system,:status,:version,now()) ON CONFLICT (account_id,system_code) DO UPDATE SET status=excluded.status,source_version=excluded.source_version,updated_at=excluded.updated_at WHERE system_access_context_projection.source_version < excluded.source_version").param("id",id).param("system",system).param("status",status).param("version",version).update(); }
  private static UUID uuid(JsonNode p,String field,UUID fallback) { try { return p.hasNonNull(field) ? UUID.fromString(p.path(field).asString()) : fallback; } catch (RuntimeException ex) { return null; } }
  private static String text(JsonNode p,String field) { return p.hasNonNull(field) ? p.path(field).asString() : null; }
  private static String requiredText(JsonNode p,String field) { String value = text(p,field); if (value == null || value.isBlank()) throw new IllegalArgumentException("missing " + field); return value; }
  private static String status(JsonNode p) { return p.hasNonNull("status") ? p.path("status").asString() : p.hasNonNull("currentStatus") ? p.path("currentStatus").asString() : null; }
}
