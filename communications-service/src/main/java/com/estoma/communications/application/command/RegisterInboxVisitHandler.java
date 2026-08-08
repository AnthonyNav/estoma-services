package com.estoma.communications.application.command;

import com.estoma.communications.application.EventToAppend;
import com.estoma.communications.application.port.InboxVisitRepository;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.RegisterInboxVisitCommand;
import com.estoma.communications.contracts.event.InboxVisitedPayload;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import org.springframework.stereotype.Component;

/**
 * Handles {@code RegisterInboxVisit} (commands.md §3). A delayed visit
 * (older than the one already stored) is not an error -- it is silently
 * ignored, and no {@code InboxVisited} event is published for it, to
 * match the same "don't let a stale message win" rule the BFF's own
 * {@code mailbox_visits_read_model} already applies on the consuming
 * side.
 */
@Component
class RegisterInboxVisitHandler implements CommandHandler {

  private final InboxVisitRepository repository;
  private final EnvelopeJsonCodec codec;

  RegisterInboxVisitHandler(InboxVisitRepository repository, EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return CommunicationsCommand.REGISTER_INBOX_VISIT.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    RegisterInboxVisitCommand command = codec.convertPayload(envelope, RegisterInboxVisitCommand.class);
    boolean applied = repository.upsertIfNewer(command.accountId(), command.systemCode(), command.visitedAt(), Instant.now());
    if (!applied) {
      return CommandHandlerResult.of(null, null);
    }
    return CommandHandlerResult.of(
        null, null,
        new EventToAppend(CommunicationsEvent.INBOX_VISITED.wireName(), new InboxVisitedPayload(command.accountId(), command.systemCode(), command.visitedAt())));
  }
}
