package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcEmailDeliveryRepositoryIT extends AbstractPostgresIT {

  @Autowired private EmailDeliveryRepository repository;
  @Autowired private CommunicationRepository communicationRepository;

  @Test
  void insertThenFindByIdRoundTrips() {
    EmailDelivery delivery = newPendingDelivery();

    repository.insert(delivery);
    EmailDelivery found = repository.findById(delivery.emailDeliveryId()).orElseThrow();

    assertThat(found.status()).isEqualTo(EmailDeliveryStatus.PENDING);
    assertThat(found.recipientAddress()).isEqualTo(delivery.recipientAddress());
  }

  @Test
  void updatePersistsAcceptedState() {
    EmailDelivery delivery = newPendingDelivery();
    repository.insert(delivery);

    EmailDelivery accepted = delivery.claimedForSending(Instant.now()).accepted(Instant.now());
    int rowsAffected = repository.update(accepted);

    assertThat(rowsAffected).isEqualTo(1);
    assertThat(repository.findById(delivery.emailDeliveryId()).orElseThrow().status()).isEqualTo(EmailDeliveryStatus.ACCEPTED);
  }

  @Test
  void claimDueTransitionsPendingRowsToSending() {
    EmailDelivery delivery = newPendingDelivery();
    repository.insert(delivery);

    List<EmailDelivery> claimed = repository.claimDue(10);

    assertThat(claimed).extracting(EmailDelivery::emailDeliveryId).contains(delivery.emailDeliveryId());
    assertThat(repository.findById(delivery.emailDeliveryId()).orElseThrow().status()).isEqualTo(EmailDeliveryStatus.SENDING);
  }

  @Test
  void claimDueIgnoresRowsAlreadyClaimed() {
    EmailDelivery delivery = newPendingDelivery();
    repository.insert(delivery);
    repository.claimDue(10);

    List<EmailDelivery> secondClaim = repository.claimDue(10);

    assertThat(secondClaim).extracting(EmailDelivery::emailDeliveryId).doesNotContain(delivery.emailDeliveryId());
  }

  private EmailDelivery newPendingDelivery() {
    Communication communication =
        Communication.requested(
            UUID.randomUUID(), UUID.randomUUID(), "LAVADO_ULTRASONICO", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
            "appointments-service", "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, Instant.now());
    communicationRepository.insert(communication);
    return EmailDelivery.generic(
        UUID.randomUUID(), communication.communicationId(), "alumno@estoma.edu", "Alumno", "Estoma", "no-reply@estoma.edu", null,
        "Cita cancelada", "Tu cita fue cancelada.", null, Instant.now());
  }
}
