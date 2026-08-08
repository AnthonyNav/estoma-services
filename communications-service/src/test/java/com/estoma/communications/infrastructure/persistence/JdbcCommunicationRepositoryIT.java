package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcCommunicationRepositoryIT extends AbstractPostgresIT {

  @Autowired private CommunicationRepository repository;

  @Test
  void insertThenFindByIdRoundTrips() {
    Communication communication = newCommunication("src-" + UUID.randomUUID());

    repository.insert(communication);
    Communication found = repository.findById(communication.communicationId()).orElseThrow();

    assertThat(found.communicationId()).isEqualTo(communication.communicationId());
    assertThat(found.status()).isEqualTo(CommunicationStatus.PROCESSING);
  }

  @Test
  void duplicateIdempotencyKeyIsRejected() {
    String sourceReferenceId = "appt-" + UUID.randomUUID();
    UUID recipientAccountId = UUID.randomUUID();
    Communication first = newCommunication(sourceReferenceId, recipientAccountId);
    repository.insert(first);
    Communication duplicate = newCommunication(sourceReferenceId, recipientAccountId);

    assertThatThrownBy(() -> repository.insert(duplicate))
        .isInstanceOf(CommunicationsRejectedException.class)
        .satisfies(e -> assertThat(((CommunicationsRejectedException) e).code()).isEqualTo(RejectionCode.COMMUNICATION_ALREADY_EXISTS));
  }

  @Test
  void updatePersistsCompletion() {
    Communication communication = newCommunication("complete-" + UUID.randomUUID(), UUID.randomUUID());
    repository.insert(communication);

    repository.update(communication.completed(Instant.now()));

    Communication found = repository.findById(communication.communicationId()).orElseThrow();
    assertThat(found.status()).isEqualTo(CommunicationStatus.COMPLETED);
    assertThat(found.completedAt()).isNotNull();
  }

  private Communication newCommunication(String sourceReferenceId) {
    return newCommunication(sourceReferenceId, UUID.randomUUID());
  }

  private Communication newCommunication(String sourceReferenceId, UUID recipientAccountId) {
    return Communication.requested(
        UUID.randomUUID(), recipientAccountId, "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
        "appointments-service", "AppointmentCancelled", sourceReferenceId, null, Instant.now());
  }
}
