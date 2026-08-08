package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcEmailDeliveryAttemptRepositoryIT extends AbstractPostgresIT {

  @Autowired private EmailDeliveryAttemptRepository repository;
  @Autowired private EmailDeliveryRepository emailDeliveryRepository;
  @Autowired private CommunicationRepository communicationRepository;

  @Test
  void insertSucceedsForAcceptedAttempt() {
    UUID emailDeliveryId = insertEmailDelivery();
    EmailDeliveryAttempt attempt =
        EmailDeliveryAttempt.accepted(
            UUID.randomUUID(), emailDeliveryId, 1, "stub", EmailContentMode.GENERIC, "provider-msg-1", Instant.now(), Instant.now());

    repository.insert(attempt);
  }

  private UUID insertEmailDelivery() {
    Communication communication =
        Communication.requested(
            UUID.randomUUID(), UUID.randomUUID(), "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
            "appointments-service", "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, Instant.now());
    communicationRepository.insert(communication);
    EmailDelivery delivery =
        EmailDelivery.generic(
            UUID.randomUUID(), communication.communicationId(), "alumno@estoma.edu", "Alumno", "Estoma", "no-reply@estoma.edu", null,
            "Cita cancelada", "Tu cita fue cancelada.", null, Instant.now());
    emailDeliveryRepository.insert(delivery);
    return delivery.emailDeliveryId();
  }
}
