package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.InboxMessageRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.inbox.InboxMessage;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcInboxMessageRepositoryIT extends AbstractPostgresIT {

  @Autowired private InboxMessageRepository repository;
  @Autowired private CommunicationRepository communicationRepository;

  @Test
  void expireDueMovesActiveMessagesPastCutoffToExpired() {
    UUID communicationId = insertCommunication();
    Instant now = Instant.now();
    InboxMessage due = InboxMessage.create(UUID.randomUUID(), communicationId, "Aviso", "Cuerpo", now.minusSeconds(60), now.minusSeconds(120));
    repository.insert(due);

    List<InboxMessageRepository.ExpiredInboxMessage> expired = repository.expireDue(now, 10);

    assertThat(expired).hasSize(1);
    assertThat(expired.get(0).communicationId()).isEqualTo(communicationId);
  }

  @Test
  void expireDueIgnoresMessagesNotYetDue() {
    UUID communicationId = insertCommunication();
    Instant now = Instant.now();
    repository.insert(InboxMessage.create(UUID.randomUUID(), communicationId, "Aviso", "Cuerpo", now.plusSeconds(3600), now));

    List<InboxMessageRepository.ExpiredInboxMessage> expired = repository.expireDue(now, 10);

    assertThat(expired).isEmpty();
  }

  private UUID insertCommunication() {
    Communication communication =
        Communication.requested(
            UUID.randomUUID(), UUID.randomUUID(), "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
            "appointments-service", "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, Instant.now());
    communicationRepository.insert(communication);
    return communication.communicationId();
  }
}
