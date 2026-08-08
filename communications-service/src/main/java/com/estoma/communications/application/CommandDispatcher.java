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

/**
 * The single transactional entry point for every durable command this
 * service handles. The only {@code @Transactional} class for the durable
 * command path: the transaction boundary is auditable in one file, and
 * handlers themselves stay free of transaction management.
 *
 * <p>The critical property this class guarantees: a functional rejection
 * ({@link CommunicationsRejectedException}) is caught HERE, inside the
 * transaction, and turned into a durable {@code Rejected} result -- never
 * rethrown. If it were rethrown, the Consumer Inbox row inserted at the top
 * of this method would roll back too, and the command would be redelivered
 * forever once the messaging shell exists (commands.md's rejections are not
 * technical errors and must not reach a DLQ).
 *
 * <p>No RabbitMQ dependency: {@link #dispatch} takes an already-decoded
 * {@link MessageEnvelope} and returns a {@link CommandOutcome}, fully
 * testable against a real database without any messaging infrastructure --
 * see execution_plan.md's note on why business logic (Phase 2) is built
 * before the messaging shell (Phase 3). The {@code AppointmentCancelled}
 * adapter deliberately does NOT go through this class -- it calls the
 * shared command logic in-process via {@code ProjectionDispatcher} instead
 * (system_contract.md "Internal Architecture").
 */
@Component
public class CommandDispatcher {

  private final InboxPort inbox;
  private final OutboxPort outbox;
  private final CommunicationsAuthorizationPolicy authorizationPolicy;
  private final EnvelopeJsonCodec codec;
  private final Map<String, CommandHandler> handlersByMessageType;

  CommandDispatcher(
      InboxPort inbox, OutboxPort outbox, CommunicationsAuthorizationPolicy authorizationPolicy, EnvelopeJsonCodec codec, List<CommandHandler> handlers) {
    this.inbox = inbox;
    this.outbox = outbox;
    this.authorizationPolicy = authorizationPolicy;
    this.codec = codec;
    this.handlersByMessageType = handlers.stream().collect(Collectors.toMap(CommandHandler::commandMessageType, Function.identity()));
  }

  @Transactional
  public CommandOutcome dispatch(MessageEnvelope envelope) {
    boolean inserted = inbox.tryInsert(ConsumerNames.COMMANDS, envelope.messageId(), envelope.correlationId());
    if (!inserted) {
      return new CommandOutcome.Duplicate();
    }

    authorizationPolicy.check(envelope.actor(), envelope.messageType());

    CommandHandler handler = handlersByMessageType.get(envelope.messageType());
    if (handler == null) {
      throw new IllegalStateException("no handler registered for messageType " + envelope.messageType());
    }

    try {
      CommandHandlerResult result = handler.handle(envelope);
      appendSucceededResult(envelope, result);
      result.events().forEach(event -> appendEvent(envelope, result.aggregateId(), result.aggregateVersion(), event));
      return new CommandOutcome.Succeeded(result.aggregateVersion());
    } catch (CommunicationsRejectedException rejected) {
      appendRejectedResult(envelope, rejected);
      return new CommandOutcome.Rejected(rejected.code());
    }
  }

  private void appendSucceededResult(MessageEnvelope envelope, CommandHandlerResult result) {
    String resultType = envelope.messageType() + "Succeeded";
    SucceededPayload payload = new SucceededPayload(result.aggregateId(), result.aggregateVersion());
    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(), resultType, 1, MessageKind.RESULT.name(), result.aggregateId(), result.aggregateVersion(),
            envelope.correlationId(), envelope.messageId(), codec.writeValueAsString(payload), Instant.now()));
  }

  private void appendRejectedResult(MessageEnvelope envelope, CommunicationsRejectedException rejected) {
    String resultType = envelope.messageType() + "Rejected";
    RejectedPayload payload = RejectedPayload.of(rejected.code().name(), rejected.getMessage());
    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(), resultType, 1, MessageKind.RESULT.name(), null, null, envelope.correlationId(), envelope.messageId(),
            codec.writeValueAsString(payload), Instant.now()));
  }

  private void appendEvent(MessageEnvelope envelope, UUID aggregateId, Integer aggregateVersion, EventToAppend event) {
    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(), event.messageType(), 1, MessageKind.EVENT.name(), aggregateId, aggregateVersion,
            envelope.correlationId(), envelope.messageId(), codec.writeValueAsString(event.payload()), Instant.now()));
  }
}
