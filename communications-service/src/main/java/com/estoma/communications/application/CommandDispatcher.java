package com.estoma.communications.application;

import com.estoma.communications.application.command.CommandHandler;
import com.estoma.communications.application.command.CommandHandlerResult;
import com.estoma.communications.application.port.InboxPort;
import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.application.port.OutboxPort;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.result.RejectedPayload;
import com.estoma.communications.contracts.result.SucceededPayload;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.communications.infrastructure.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class CommandDispatcher {

  private final InboxPort inbox;
  private final OutboxPort outbox;
  private final CommunicationsAuthorizationPolicy authorizationPolicy;
  private final EnvelopeJsonCodec codec;
  private final Map<String, CommandHandler> handlersByMessageType;

  CommandDispatcher(
      InboxPort inbox, OutboxPort outbox, CommunicationsAuthorizationPolicy authorizationPolicy,
      EnvelopeJsonCodec codec, List<CommandHandler> handlers) {
    this.inbox = inbox;
    this.outbox = outbox;
    this.authorizationPolicy = authorizationPolicy;
    this.codec = codec;
    this.handlersByMessageType = handlers.stream().collect(Collectors.toMap(CommandHandler::commandMessageType, Function.identity()));
  }

  @Transactional
  public CommandOutcome dispatch(MessageEnvelope envelope) {
    return dispatch(envelope, true);
  }

  /**
   * Dispatches a command initiated by this service itself.
   *
   * <p>Internal work has no remote caller waiting for a command result, so it
   * deliberately persists only the domain events produced by the handler. The
   * normal {@link #dispatch(MessageEnvelope)} path remains responsible for
   * durable succeeded and rejected result envelopes.
   */
  @Transactional
  public CommandOutcome dispatchInternal(MessageEnvelope envelope) {
    return dispatch(envelope, false);
  }

  private CommandOutcome dispatch(MessageEnvelope envelope, boolean appendResult) {
    boolean inserted = inbox.tryInsert(ConsumerNames.COMMANDS, envelope.messageId(), envelope.correlationId());
    if (!inserted) return new CommandOutcome.Duplicate();

    try {
      authorizationPolicy.check(envelope.actor(), envelope.messageType());
      CommandHandler handler = handlersByMessageType.get(envelope.messageType());
      if (handler == null) throw new IllegalStateException("no handler registered for messageType " + envelope.messageType());
      CommandHandlerResult result = handler.handle(envelope);
      if (appendResult) appendSucceededResult(envelope, result);
      result.events().forEach(event -> appendEvent(envelope, result.aggregateId(), result.aggregateVersion(), event));
      return new CommandOutcome.Succeeded(result.aggregateVersion());
    } catch (CommunicationsRejectedException rejected) {
      if (appendResult) appendRejectedResult(envelope, rejected);
      return new CommandOutcome.Rejected(rejected.code());
    }
  }

  private void appendSucceededResult(MessageEnvelope envelope, CommandHandlerResult result) {
    String resultType = envelope.messageType() + "Succeeded";
    SucceededPayload payload = new SucceededPayload(result.aggregateId(), result.aggregateVersion());
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), resultType, 1, MessageKind.RESULT.name(), result.aggregateId(), result.aggregateVersion(),
        envelope.correlationId(), envelope.messageId(), codec.writeValueAsString(payload), Instant.now(),
        envelope.operationId(), envelope.idempotencyKey(), envelope.actor(), envelope.producer()));
  }

  private void appendRejectedResult(MessageEnvelope envelope, CommunicationsRejectedException rejected) {
    String resultType = envelope.messageType() + "Rejected";
    RejectedPayload payload = RejectedPayload.of(rejected.code().name(), rejected.getMessage());
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), resultType, 1, MessageKind.RESULT.name(), null, null,
        envelope.correlationId(), envelope.messageId(), codec.writeValueAsString(payload), Instant.now(),
        envelope.operationId(), envelope.idempotencyKey(), envelope.actor(), envelope.producer()));
  }

  private void appendEvent(MessageEnvelope envelope, UUID aggregateId, Integer aggregateVersion, EventToAppend event) {
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), event.messageType(), 1, MessageKind.EVENT.name(), aggregateId, aggregateVersion,
        envelope.correlationId(), envelope.messageId(), codec.writeValueAsString(event.payload()), Instant.now(),
        null, null, envelope.actor(), null));
  }
}
