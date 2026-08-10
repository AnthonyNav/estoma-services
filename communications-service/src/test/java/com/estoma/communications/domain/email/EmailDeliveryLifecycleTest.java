package com.estoma.communications.domain.email;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.contracts.EmailAttemptStatus;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailDeliveryLifecycleTest {
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void startsAReadyAttemptAndConsumesOneCyclePositionOnlyAtCallStart() {
    UUID deliveryId = UUID.randomUUID();
    UUID token = UUID.randomUUID();
    EmailDelivery delivery = EmailDelivery.generic(deliveryId, UUID.randomUUID(), "a@example.test", "A", "Estoma",
        "no-reply@example.test", null, "Subject", "Body", null, NOW);
    EmailDelivery claimed = delivery.claimed(token, NOW, NOW.plusSeconds(30), "worker-1");
    EmailDelivery started = claimed.startedSending(NOW.plusSeconds(1));

    assertThat(claimed.status()).isEqualTo(EmailDeliveryStatus.PENDING);
    assertThat(claimed.attemptCount()).isZero();
    assertThat(started.status()).isEqualTo(EmailDeliveryStatus.SENDING);
    assertThat(started.attemptCount()).isEqualTo(1);
    assertThat(started.retryCycle()).isEqualTo(1);
    assertThat(started.cycleAttemptCount()).isEqualTo(1);
  }

  @Test
  void preservesHistoryWhenManualRetryStartsANewCycle() {
    EmailDelivery delivery = EmailDelivery.generic(UUID.randomUUID(), UUID.randomUUID(), "a@example.test", "A", "Estoma",
        "no-reply@example.test", null, "Subject", "Body", null, NOW)
        .claimed(UUID.randomUUID(), NOW, NOW.plusSeconds(30), "worker-1")
        .startedSending(NOW.plusSeconds(1))
        .failedTerminal("PERMANENT", "rejected", NOW.plusSeconds(2));

    EmailDelivery retried = delivery.retriedByOperator(NOW.plusSeconds(3));

    assertThat(retried.status()).isEqualTo(EmailDeliveryStatus.PENDING);
    assertThat(retried.attemptCount()).isEqualTo(1);
    assertThat(retried.retryCycle()).isEqualTo(2);
    assertThat(retried.cycleAttemptCount()).isZero();
  }

  @Test
  void terminalAttemptTransitionsReleaseTheirClaim() {
    UUID token = UUID.randomUUID();
    EmailDeliveryAttempt ready = EmailDeliveryAttempt.readyToSend(UUID.randomUUID(), UUID.randomUUID(), 1, 1, 1,
        token, "fake", EmailContentMode.GENERIC, NOW);

    assertThat(ready.status()).isEqualTo(EmailAttemptStatus.READY_TO_SEND);
    EmailDeliveryAttempt started = ready.callStarted(NOW.plusSeconds(1));
    assertThat(started.status()).isEqualTo(EmailAttemptStatus.CALL_STARTED);
    assertThat(started.completedAt()).isNull();
    assertThat(started.accepted("provider-id", NOW.plusSeconds(2)).status()).isEqualTo(EmailAttemptStatus.ACCEPTED);
    assertThat(started.accepted("provider-id", NOW.plusSeconds(2)).claimToken()).isNull();
    assertThat(started.failed("REJECTED", "rejected", NOW.plusSeconds(2)).claimToken()).isNull();
    assertThat(started.indeterminate("TIMEOUT", "unknown", NOW.plusSeconds(2)).status())
        .isEqualTo(EmailAttemptStatus.INDETERMINATE);
    assertThat(started.indeterminate("TIMEOUT", "unknown", NOW.plusSeconds(2)).claimToken()).isNull();
  }
}
