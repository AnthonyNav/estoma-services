package com.estoma.communications.application.command;

import com.estoma.communications.application.EventToAppend;
import com.estoma.communications.application.port.InboxMessageRepository;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.ExpireInboxMessagesCommand;
import com.estoma.communications.contracts.event.InboxMessageExpiredPayload;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Handles {@code ExpireInboxMessages} (commands.md §3): one {@code InboxMessageExpired} event per expired message. */
@Component
class ExpireInboxMessagesHandler implements CommandHandler {

  private final InboxMessageRepository repository;
  private final EnvelopeJsonCodec codec;

  ExpireInboxMessagesHandler(InboxMessageRepository repository, EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return CommunicationsCommand.EXPIRE_INBOX_MESSAGES.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    ExpireInboxMessagesCommand command = codec.convertPayload(envelope, ExpireInboxMessagesCommand.class);
    if (command.batchSize() <= 0) {
      throw new CommunicationsRejectedException(RejectionCode.INVALID_BATCH_SIZE, "batchSize must be positive");
    }

    Instant now = Instant.now();
    List<InboxMessageRepository.ExpiredInboxMessage> expired = repository.expireDue(command.cutoff(), command.batchSize());
    EventToAppend[] events =
        expired.stream()
            .map(
                message ->
                    new EventToAppend(
                        CommunicationsEvent.INBOX_MESSAGE_EXPIRED.wireName(),
                        new InboxMessageExpiredPayload(message.inboxMessageId(), message.communicationId(), now)))
            .toArray(EventToAppend[]::new);

    return CommandHandlerResult.of(null, null, events);
  }
}
