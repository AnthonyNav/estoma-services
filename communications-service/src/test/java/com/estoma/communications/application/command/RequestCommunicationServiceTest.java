package com.estoma.communications.application.command;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.EmailSenderDirectory;
import com.estoma.communications.application.port.InboxMessageRepository;
import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.testsupport.Envelopes;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RequestCommunicationServiceTest {
  private final CommunicationRepository communications = mock(CommunicationRepository.class);
  private final InboxMessageRepository inboxMessages = mock(InboxMessageRepository.class);
  private final EmailDeliveryRepository deliveries = mock(EmailDeliveryRepository.class);
  private final RecipientProjectionRepository recipients = mock(RecipientProjectionRepository.class);
  private final EmailSenderDirectory senders = mock(EmailSenderDirectory.class);
  private final RequestCommunicationService service =
      new RequestCommunicationService(communications, inboxMessages, deliveries, recipients, senders);

  @Test
  void rejectsMismatchedChannelsBeforeAnyPersistenceOrRecipientLookup() {
    RequestCommunicationCommand command = inboxCommand(List.of("EMAIL"));

    assertRejected(command, RejectionCode.INVALID_CONTENT);

    verifyNoInteractions(communications, inboxMessages, deliveries, recipients, senders);
  }

  @Test
  void translatesSenderResolutionFailureBeforeCommunicationInsert() {
    RequestCommunicationCommand command = emailCommand();
    var envelope = envelope();
    when(communications.findBySourceAndIdempotency(command.sourceService(), envelope.idempotencyKey())).thenReturn(Optional.empty());
    when(recipients.findByAccountId(command.recipientAccountId())).thenReturn(Optional.of(
        new RecipientProjectionRepository.RecipientProjectionRecord(command.recipientAccountId(), UUID.randomUUID(), "Recipient",
            "recipient@estoma.edu", "ACTIVE", "ACTIVE", 1, 1)));
    when(senders.resolve(null)).thenThrow(new IllegalArgumentException("unknown sender"));

    assertThatThrownBy(() -> service.create(command, envelope))
        .isInstanceOfSatisfying(CommunicationsRejectedException.class,
            rejected -> assertThat(rejected.code()).isEqualTo(RejectionCode.INVALID_CONTENT));

    verify(communications, never()).insert(org.mockito.ArgumentMatchers.any());
    verifyNoInteractions(inboxMessages, deliveries);
  }

  private void assertRejected(RequestCommunicationCommand command, RejectionCode code) {
    assertThatThrownBy(() -> service.create(command, envelope()))
        .isInstanceOfSatisfying(CommunicationsRejectedException.class,
            rejected -> assertThat(rejected.code()).isEqualTo(code));
  }

  private RequestCommunicationCommand inboxCommand(List<String> channels) {
    return new RequestCommunicationCommand(UUID.randomUUID(), "system", "type", CommunicationPriority.NORMAL, "source", "reference", "id", null,
        new RequestCommunicationCommand.InboxContent("title", "body", null), null, channels);
  }

  private RequestCommunicationCommand emailCommand() {
    return new RequestCommunicationCommand(UUID.randomUUID(), "system", "type", CommunicationPriority.NORMAL, "source", "reference", "id", null,
        null, new RequestCommunicationCommand.EmailContent(EmailContentMode.GENERIC, null, "subject", "body", null, null, null));
  }

  private com.estoma.communications.contracts.MessageEnvelope envelope() {
    return Envelopes.command(CommunicationsCommand.REQUEST_COMMUNICATION.wireName(), new Object(), UUID.randomUUID(), UUID.randomUUID());
  }
}
