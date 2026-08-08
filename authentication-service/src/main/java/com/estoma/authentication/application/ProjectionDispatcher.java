package com.estoma.authentication.application;

import com.estoma.authentication.application.port.InboxPort;
import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import com.estoma.authentication.application.projection.ProjectionHandler;
import com.estoma.authentication.application.projection.ProjectionHandlerResult;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single transactional entry point for every consumed event this
 * service handles ({@code PersonCreated}/{@code PersonActivated}/{@code
 * PersonDeactivated}, {@code StudentNumberCorrected}). Consumer Inbox
 * first, same as {@link CommandDispatcher}; no result is ever produced
 * (events have no reply channel), only zero or more derived events.
 *
 * <p>No RabbitMQ dependency, fully testable without any messaging
 * infrastructure -- see execution_plan.md's note on business logic before
 * the messaging shell.
 */
@Component
public class ProjectionDispatcher {

  private final InboxPort inbox;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;
  private final Map<String, ProjectionHandler> handlersByMessageType;

  ProjectionDispatcher(InboxPort inbox, OutboxPort outbox, EnvelopeJsonCodec codec, List<ProjectionHandler> handlers) {
    this.inbox = inbox;
    this.outbox = outbox;
    this.codec = codec;
    this.handlersByMessageType =
        handlers.stream()
            .flatMap(handler -> handler.consumedMessageTypes().stream().map(type -> Map.entry(type, handler)))
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
  }

  @Transactional
  public ProjectionOutcome dispatch(MessageEnvelope envelope) {
    boolean inserted = inbox.tryInsert(ConsumerNames.EVENTS, envelope.messageId(), envelope.correlationId());
    if (!inserted) {
      return new ProjectionOutcome.Duplicate();
    }

    ProjectionHandler handler = handlersByMessageType.get(envelope.messageType());
    if (handler == null) {
      throw new IllegalStateException("no projection handler registered for messageType " + envelope.messageType());
    }

    ProjectionHandlerResult result = handler.handle(envelope);
    result.events().forEach(event -> appendEvent(envelope, event));
    return new ProjectionOutcome.Applied();
  }

  private void appendEvent(MessageEnvelope envelope, EventToAppend event) {
    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(), event.messageType(), 1, MessageKind.EVENT.name(), envelope.aggregateId(), envelope.aggregateVersion(),
            envelope.correlationId(), envelope.messageId(), codec.writeValueAsString(event.payload()), Instant.now()));
  }
}
