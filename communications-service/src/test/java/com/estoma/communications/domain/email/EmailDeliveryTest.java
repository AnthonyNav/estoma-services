package com.estoma.communications.domain.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.domain.CommunicationsRejectedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailDeliveryTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void genericEmailStartsPending() {
    EmailDelivery delivery = newGenericDelivery();

    assertThat(delivery.status()).isEqualTo(EmailDeliveryStatus.PENDING);
    assertThat(delivery.attemptCount()).isZero();
  }

  @Test
  void acceptedIncrementsAttemptCountAndSetsAcceptedAt() {
    EmailDelivery delivery = newGenericDelivery().claimedForSending(NOW.plusSeconds(1)).accepted(NOW.plusSeconds(2));

    assertThat(delivery.status()).isEqualTo(EmailDeliveryStatus.ACCEPTED);
    assertThat(delivery.attemptCount()).isEqualTo(1);
    assertThat(delivery.acceptedAt()).isEqualTo(NOW.plusSeconds(2));
  }

  @Test
  void retryScheduledKeepsDeliveryRetryable() {
    EmailDelivery delivery =
        newGenericDelivery().claimedForSending(NOW.plusSeconds(1)).retryScheduled("PROVIDER_TIMEOUT", "timed out", NOW.plusSeconds(60), NOW.plusSeconds(2));

    assertThat(delivery.status()).isEqualTo(EmailDeliveryStatus.RETRY_SCHEDULED);
    assertThat(delivery.attemptCount()).isEqualTo(1);
    assertThat(delivery.nextAttemptAt()).isEqualTo(NOW.plusSeconds(60));
  }

  @Test
  void alreadyAcceptedCannotBeRetried() {
    EmailDelivery accepted = newGenericDelivery().claimedForSending(NOW).accepted(NOW);

    assertThatThrownBy(() -> accepted.retriedByOperator(NOW)).isInstanceOf(CommunicationsRejectedException.class);
  }

  @Test
  void failedDeliveryCanBeRetriedByOperator() {
    EmailDelivery failed = newGenericDelivery().claimedForSending(NOW).failedTerminal("PROVIDER_REJECTED", "rejected", NOW);

    EmailDelivery retried = failed.retriedByOperator(NOW.plusSeconds(10));

    assertThat(retried.status()).isEqualTo(EmailDeliveryStatus.PENDING);
  }

  private static EmailDelivery newGenericDelivery() {
    return EmailDelivery.generic(
        UUID.randomUUID(), UUID.randomUUID(), "alumno@estoma.edu", "Alumno", "Estoma", "no-reply@estoma.edu", null, "Cita cancelada",
        "Tu cita fue cancelada por la clínica.", null, NOW);
  }
}
