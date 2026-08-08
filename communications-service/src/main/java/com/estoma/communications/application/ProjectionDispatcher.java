package com.estoma.communications.application;

import com.estoma.communications.application.port.InboxPort;
import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.application.port.OutboxPort;
import com.estoma.communications.application.projection.ProjectionHandler;
import com.estoma.communications.application.projection.ProjectionHandlerResult;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single transactional entry point for every consumed event this
 * service handles ({@code recipient_projection} updates, and the {@code
 * AppointmentCancelled} adapter). Consumer Inbox first, same as {@link
 * CommandDispatcher}, but keyed by {@code handler.consumerName()} rather
 * than one shared constant -- see {@link ProjectionHandler}'s javadoc.
 * No result is ever produced (events have no reply channel), only zero or
 * more derived events.
 *
 * <p>No RabbitMQ dependency, fully testable without any messaging
 * infrastructure -- see execution_plan.md's note on business logic before
 * the messaging shell (Phase 3).
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
    ProjectionHandler handler = handlersByMessageType.get(envelope.messageType());
    if (handler == null) {
      throw new IllegalStateException("no projection handler registered for messageType " + envelope.messageType());
    }

    boolean inserted = inbox.tryInsert(handler.consumerName(), envelope.messageId(), envelope.correlationId());
    if (!inserted) {
      return new ProjectionOutcome.Duplicate();
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
