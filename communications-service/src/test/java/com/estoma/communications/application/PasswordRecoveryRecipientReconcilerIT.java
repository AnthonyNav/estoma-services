package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.adapter.PasswordRecoveryRecipientReconciler;
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

class PasswordRecoveryRecipientReconcilerIT extends AbstractPostgresIT {

  @Autowired ProjectionDispatcher dispatcher;
  @Autowired PasswordRecoveryRecipientReconciler reconciler;
  @Autowired RecipientProjectionRepository recipients;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void reset() {
    jdbc.sql("TRUNCATE TABLE email_delivery_attempts,email_deliveries,communications,recipient_projection,processed_messages,outbox_messages CASCADE").update();
  }

  @Test
  void waitingRecoveryBecomesPendingWhenAccountAndPersonProjectionsConverge() {
    UUID recoveryId=UUID.randomUUID(), accountId=UUID.randomUUID(), personId=UUID.randomUUID();
    Instant now=Instant.now();
    dispatcher.dispatch(event(recoveryId, accountId, personId, now.plusSeconds(600), now));

    recipients.upsertAccountIfNewer(accountId, personId, "ACTIVE", 1, Instant.now());
    recipients.updatePersonIfNewer(personId, "Alumno", "alumno@estoma.edu", "ACTIVE", 1, Instant.now());
    reconciler.reconcile();

    assertThat(jdbc.sql("SELECT status::text FROM email_deliveries WHERE recovery_id=:id").param("id",recoveryId).query(String.class).single())
        .isEqualTo("PENDING");
    assertThat(jdbc.sql("SELECT recipient_address FROM email_deliveries WHERE recovery_id=:id").param("id",recoveryId).query(String.class).single())
        .isEqualTo("alumno@estoma.edu");
  }

  @Test
  void expiredWaitingRecoveryFailsWithoutProviderAttempt() {
    UUID recoveryId=UUID.randomUUID(), accountId=UUID.randomUUID(), personId=UUID.randomUUID();
    Instant requested=Instant.now().minusSeconds(700);
    dispatcher.dispatch(event(recoveryId, accountId, personId, requested.plusSeconds(600), requested));

    reconciler.reconcile();

    assertThat(jdbc.sql("SELECT status::text FROM email_deliveries WHERE recovery_id=:id").param("id",recoveryId).query(String.class).single())
        .isEqualTo("FAILED");
    assertThat(jdbc.sql("SELECT attempt_count FROM email_deliveries WHERE recovery_id=:id").param("id",recoveryId).query(Integer.class).single())
        .isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM email_delivery_attempts").query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT status::text FROM communications WHERE idempotency_key=:id").param("id",recoveryId.toString()).query(String.class).single())
        .isEqualTo("FAILED");
    assertThat(jdbc.sql("SELECT message_type FROM outbox_messages WHERE message_type='EmailDeliveryFailed'").query(String.class).list())
        .contains("EmailDeliveryFailed");
    assertThat(jdbc.sql("SELECT payload::text FROM outbox_messages WHERE message_type='EmailDeliveryFailed'").query(String.class).single())
        .contains("RECOVERY_EXPIRED");
  }

  private MessageEnvelope event(UUID recoveryId,UUID accountId,UUID personId,Instant expiresAt,Instant requestedAt) {
    return new MessageEnvelope(UUID.randomUUID(),"PasswordRecoveryRequested",1,MessageKind.EVENT,"authentication",requestedAt,
        UUID.randomUUID(),null,null,null,null,null,Actor.system("authentication"),
        mapper.valueToTree(new PasswordRecoveryRequestedPayload(recoveryId,accountId,personId,expiresAt,requestedAt)));
  }
}
