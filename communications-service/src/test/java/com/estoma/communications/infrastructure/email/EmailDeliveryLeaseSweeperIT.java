package com.estoma.communications.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.EmailAttemptStatus;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "estoma.communications.email.worker.enabled=true")
class EmailDeliveryLeaseSweeperIT extends AbstractPostgresIT {
  @Autowired private EmailDeliveryLeaseSweeper sweeper;
  @Autowired private EmailDeliveryRepository deliveries;
  @Autowired private EmailDeliveryAttemptRepository attempts;
  @Autowired private CommunicationRepository communications;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void isolateDeliveryData() {
    jdbc.sql("DELETE FROM email_delivery_administrative_actions").update();
    jdbc.sql("DELETE FROM email_delivery_attempts").update();
    jdbc.sql("DELETE FROM email_deliveries").update();
    jdbc.sql("DELETE FROM inbox_messages").update();
    jdbc.sql("DELETE FROM communications").update();
  }

  @Test
  void expiredLeasesPreserveUnstartedWorkAndQuarantineStartedCalls() {
    EmailDelivery started = claimedDelivery();
    EmailDelivery ready = claimedDelivery();
    EmailDelivery noAttempt = claimedDelivery();
    EmailDeliveryAttempt startedAttempt = EmailDeliveryAttempt.readyToSend(UUID.randomUUID(), started.emailDeliveryId(), 1, 1, 1,
        started.claimToken(), "stub", EmailContentMode.GENERIC, Instant.now());
    attempts.insert(startedAttempt);
    assertThat(attempts.update(startedAttempt.callStarted(Instant.now()))).isEqualTo(1);
    EmailDeliveryAttempt readyAttempt = EmailDeliveryAttempt.readyToSend(UUID.randomUUID(), ready.emailDeliveryId(), 1, 1, 1,
        ready.claimToken(), "stub", EmailContentMode.GENERIC, Instant.now());
    attempts.insert(readyAttempt);
    expire(started, ready, noAttempt);

    sweeper.sweep();

    assertThat(deliveries.findById(started.emailDeliveryId()).orElseThrow().status()).isEqualTo(EmailDeliveryStatus.INDETERMINATE);
    assertThat(attemptStatus(started.emailDeliveryId())).isEqualTo(EmailAttemptStatus.INDETERMINATE.name());
    assertThat(deliveries.findById(ready.emailDeliveryId()).orElseThrow().status()).isEqualTo(EmailDeliveryStatus.PENDING);
    assertThat(deliveries.findById(ready.emailDeliveryId()).orElseThrow().attemptCount()).isZero();
    assertThat(attemptCount(ready.emailDeliveryId())).isEqualTo(1);
    assertThat(attemptStatus(ready.emailDeliveryId())).isEqualTo(EmailAttemptStatus.READY_TO_SEND.name());
    assertThat(attemptClaimToken(ready.emailDeliveryId())).isNull();
    assertThat(deliveries.findById(noAttempt.emailDeliveryId()).orElseThrow().status()).isEqualTo(EmailDeliveryStatus.PENDING);

    jdbc.sql("UPDATE email_deliveries SET next_attempt_at = now() - interval '1 second' WHERE email_delivery_id IN (:ready, :noAttempt)")
        .param("ready", ready.emailDeliveryId()).param("noAttempt", noAttempt.emailDeliveryId()).update();
    assertThat(deliveries.claimDue(10)).extracting(EmailDelivery::emailDeliveryId)
        .contains(ready.emailDeliveryId(), noAttempt.emailDeliveryId()).doesNotContain(started.emailDeliveryId());
  }

  private EmailDelivery claimedDelivery() {
    Communication communication = Communication.requested(UUID.randomUUID(), UUID.randomUUID(), "system", "type", CommunicationPriority.NORMAL,
        "source", "reference", UUID.randomUUID().toString(), null, Instant.now());
    communications.insert(communication);
    EmailDelivery delivery = EmailDelivery.generic(UUID.randomUUID(), communication.communicationId(), "student@example.test", "Student",
        "Estoma", "no-reply@example.test", null, "subject", "body", null, Instant.now());
    deliveries.insert(delivery);
    return deliveries.claimDue(1).getFirst();
  }

  private void expire(EmailDelivery... deliveries) {
    for (EmailDelivery delivery : deliveries) {
      jdbc.sql("UPDATE email_deliveries SET lease_until = now() - interval '1 second' WHERE email_delivery_id = :id")
          .param("id", delivery.emailDeliveryId()).update();
    }
  }

  private String attemptStatus(UUID deliveryId) {
    return jdbc.sql("SELECT status::text FROM email_delivery_attempts WHERE email_delivery_id = :id")
        .param("id", deliveryId).query(String.class).single();
  }

  private long attemptCount(UUID deliveryId) {
    return jdbc.sql("SELECT count(*) FROM email_delivery_attempts WHERE email_delivery_id = :id")
        .param("id", deliveryId).query(Long.class).single();
  }

  private UUID attemptClaimToken(UUID deliveryId) {
    return jdbc.sql("SELECT claim_token FROM email_delivery_attempts WHERE email_delivery_id = :id")
        .param("id", deliveryId).query(UUID.class).optional().orElse(null);
  }
}
