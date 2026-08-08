package com.estoma.communications.domain.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.domain.CommunicationsRejectedException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CommunicationTest {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void requestedStartsProcessing() {
    Communication communication = newCommunication();

    assertThat(communication.status()).isEqualTo(CommunicationStatus.PROCESSING);
    assertThat(communication.completedAt()).isNull();
    assertThat(communication.failedAt()).isNull();
  }

  @Test
  void completedTransitionsFromProcessing() {
    Communication communication = newCommunication().completed(NOW.plusSeconds(5));

    assertThat(communication.status()).isEqualTo(CommunicationStatus.COMPLETED);
    assertThat(communication.completedAt()).isEqualTo(NOW.plusSeconds(5));
  }

  @Test
  void failedTransitionsFromProcessing() {
    Communication communication = newCommunication().failed(NOW.plusSeconds(5));

    assertThat(communication.status()).isEqualTo(CommunicationStatus.FAILED);
    assertThat(communication.failedAt()).isEqualTo(NOW.plusSeconds(5));
  }

  @Test
  void cannotCompleteTwice() {
    Communication completed = newCommunication().completed(NOW);

    assertThatThrownBy(() -> completed.completed(NOW)).isInstanceOf(CommunicationsRejectedException.class);
  }

  private static Communication newCommunication() {
    return Communication.requested(
        UUID.randomUUID(), UUID.randomUUID(), "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
        "appointments-service", "AppointmentCancelled", UUID.randomUUID().toString(), null, NOW);
  }
}
