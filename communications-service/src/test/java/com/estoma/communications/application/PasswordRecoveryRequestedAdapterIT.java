package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.consumed.PasswordRecoveryRequestedPayload;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class PasswordRecoveryRequestedAdapterIT extends AbstractPostgresIT {

  @Autowired ProjectionDispatcher dispatcher;
  @Autowired RecipientProjectionRepository recipients;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE email_delivery_attempts,email_deliveries,inbox_messages,inbox_visits,communications,recipient_projection,processed_messages,outbox_messages CASCADE").update();
  }

  @Test
  void missingProjectionCreatesWaitingDeliveryWithoutAnySecretOrRenderedContent() {
    UUID recoveryId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    Instant now = Instant.now();

    dispatcher.dispatch(recoveryEvent(recoveryId, accountId, personId, now.plusSeconds(600), now, UUID.randomUUID()));

    var row = jdbc.sql("""
        SELECT d.status::text,d.recovery_id,d.recipient_address,d.subject,d.text_body,d.html_body,d.template_data,d.template_code,
               c.source_service,c.source_reference_type,c.source_reference_id
        FROM email_deliveries d JOIN communications c ON c.communication_id=d.communication_id
        WHERE d.recovery_id=:recovery
        """).param("recovery", recoveryId)
        .query((rs,n) -> new RecoveryRow(
            rs.getString(1), rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getString(6), rs.getString(7), rs.getString(8), rs.getString(9), rs.getString(10), rs.getString(11)))
        .single();

    assertThat(row.status).isEqualTo("WAITING_FOR_RECIPIENT");
    assertThat(row.recoveryId).isEqualTo(recoveryId);
    assertThat(row.recipientAddress).isNull();
    assertThat(row.subject).isNull();
    assertThat(row.textBody).isNull();
    assertThat(row.htmlBody).isNull();
    assertThat(row.templateData).isNull();
    assertThat(row.templateCode).isEqualTo("PASSWORD_RECOVERY");
    assertThat(row.sourceService).isEqualTo("authentication");
    assertThat(row.sourceType).isEqualTo("PasswordRecoveryRequested");
    assertThat(row.sourceId).isEqualTo(recoveryId.toString());
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE payload::text LIKE '%123456%'").query(Long.class).single()).isZero();
  }

  @Test
  void completeProjectionCreatesPendingDeliveryButStillNoSecretContent() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    recipients.upsertAccountIfNewer(accountId, personId, "ACTIVE", 1, Instant.now());
    recipients.updatePersonIfNewer(personId, "Alumno", "alumno@estoma.edu", "ACTIVE", 1, Instant.now());
    UUID recoveryId = UUID.randomUUID();
    Instant now = Instant.now();

    dispatcher.dispatch(recoveryEvent(recoveryId, accountId, personId, now.plusSeconds(600), now, UUID.randomUUID()));

    assertThat(jdbc.sql("SELECT status::text FROM email_deliveries WHERE recovery_id=:id").param("id", recoveryId).query(String.class).single())
        .isEqualTo("PENDING");
    assertThat(jdbc.sql("SELECT recipient_address FROM email_deliveries WHERE recovery_id=:id").param("id", recoveryId).query(String.class).single())
        .isEqualTo("alumno@estoma.edu");
    assertThat(jdbc.sql("SELECT text_body IS NULL FROM email_deliveries WHERE recovery_id=:id").param("id", recoveryId).query(Boolean.class).single())
        .isTrue();
  }

  @Test
  void sameRecoveryUnderDifferentMessageIdsRemainsOneCommunicationAndOneDelivery() {
    UUID recoveryId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    Instant now = Instant.now();

    dispatcher.dispatch(recoveryEvent(recoveryId, accountId, personId, now.plusSeconds(600), now, UUID.randomUUID()));
    dispatcher.dispatch(recoveryEvent(recoveryId, accountId, personId, now.plusSeconds(600), now, UUID.randomUUID()));

    assertThat(jdbc.sql("SELECT count(*) FROM communications WHERE idempotency_key=:id").param("id", recoveryId.toString()).query(Long.class).single())
        .isEqualTo(1);
    assertThat(jdbc.sql("SELECT count(*) FROM email_deliveries WHERE recovery_id=:id").param("id", recoveryId).query(Long.class).single())
        .isEqualTo(1);
  }

  private MessageEnvelope recoveryEvent(
      UUID recoveryId, UUID accountId, UUID personId, Instant expiresAt, Instant requestedAt, UUID messageId) {
    return new MessageEnvelope(
        messageId, "PasswordRecoveryRequested", 1, MessageKind.EVENT, "authentication", requestedAt,
        UUID.randomUUID(), null, null, null, null, null, Actor.system("authentication"),
        mapper.valueToTree(new PasswordRecoveryRequestedPayload(recoveryId, accountId, personId, expiresAt, requestedAt)));
  }

  private record RecoveryRow(
      String status, UUID recoveryId, String recipientAddress, String subject, String textBody, String htmlBody,
      String templateData, String templateCode, String sourceService, String sourceType, String sourceId) {}
}
