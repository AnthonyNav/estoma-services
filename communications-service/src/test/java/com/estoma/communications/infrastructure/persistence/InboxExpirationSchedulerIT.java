package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.InboxMessageRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.inbox.InboxMessage;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = {
    "estoma.communications.inbox.expiration.poll-interval-ms=3600000",
    "estoma.communications.inbox.expiration.initial-delay-ms=3600000"
})
class InboxExpirationSchedulerIT extends AbstractPostgresIT {

  @Autowired private InboxExpirationScheduler scheduler;
  @Autowired private InboxMessageRepository inboxMessageRepository;
  @Autowired private CommunicationRepository communicationRepository;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void isolateSchedulerState() {
    jdbc.sql("TRUNCATE TABLE outbox_messages, inbox_messages, communications CASCADE").update();
  }

  @Test
  void expireDueMessagesMarksOverdueMessagesExpired() {
    long resultsBefore = outboxCount("RESULT", "ExpireInboxMessagesSucceeded");
    long eventsBefore = outboxCount("EVENT", CommunicationsEvent.INBOX_MESSAGE_EXPIRED.wireName());
    Communication communication =
        Communication.requested(
            UUID.randomUUID(), UUID.randomUUID(), "wash-ultrasonico", "AppointmentCancelledNotice", CommunicationPriority.NORMAL,
            "appointments-service", "AppointmentCancelled", "appt-" + UUID.randomUUID(), null, Instant.now());
    communicationRepository.insert(communication);
    Instant now = Instant.now();
    InboxMessage message =
        InboxMessage.create(UUID.randomUUID(), communication.communicationId(), "Aviso", "Cuerpo", now.minusSeconds(60), now.minusSeconds(120));
    inboxMessageRepository.insert(message);

    scheduler.expireDueMessages();

    assertThat(inboxMessageRepository.expireDue(now, 10)).isEmpty();
    assertThat(outboxCount("RESULT", "ExpireInboxMessagesSucceeded")).isEqualTo(resultsBefore);
    assertThat(outboxCount("EVENT", CommunicationsEvent.INBOX_MESSAGE_EXPIRED.wireName())).isEqualTo(eventsBefore + 1);
  }

  private long outboxCount(String kind, String messageType) {
    return jdbc
        .sql("SELECT count(*) FROM outbox_messages WHERE message_kind = :kind AND message_type = :messageType")
        .param("kind", kind)
        .param("messageType", messageType)
        .query(Long.class)
        .single();
  }
}
