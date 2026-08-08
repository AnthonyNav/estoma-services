package com.estoma.authentication.application;

import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import com.estoma.authentication.application.rpc.RpcHandlerOutcome;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single transactional entry point for every ephemeral security RPC
 * operation (system_contract.md "RPC Security Rules"). Unlike {@link
 * CommandDispatcher}, there is no Consumer Inbox (RPC is never durably
 * redelivered) and no Result is ever appended to the Outbox (RPC never
 * publishes to {@code platform.results}) -- only the response, returned
 * directly, and zero or more non-sensitive derived events.
 *
 * <p>A functional rejection is still caught here, inside the transaction,
 * for the same reason as {@link CommandDispatcher}: state a handler already
 * applied before deciding to reject (e.g. incrementing {@code
 * failed_attempts} before throwing {@code INVALID_CREDENTIALS}) must still
 * commit.
 *
 * <p>Generic over the response type because each RPC operation has its own
 * request/response shape (commands.md §7-§8) -- one handler bean per
 * operation, invoked directly by name rather than looked up by message
 * type, since there are exactly eight, all statically known.
 *
 * <p>No RabbitMQ dependency: {@link #execute} takes a handler call already
 * decoupled from any transport, fully testable against a real database
 * without Direct Reply-To or any broker.
 */
@Component
public class RpcDispatcher {

  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;

  RpcDispatcher(OutboxPort outbox, EnvelopeJsonCodec codec) {
    this.outbox = outbox;
    this.codec = codec;
  }

  @Transactional
  public <T> RpcOutcome<T> execute(UUID correlationId, Supplier<RpcHandlerOutcome<T>> handlerCall) {
    try {
      RpcHandlerOutcome<T> outcome = handlerCall.get();
      outcome.events().forEach(event -> appendEvent(correlationId, outcome.aggregateId(), outcome.aggregateVersion(), event));
      return new RpcOutcome.Success<>(outcome.response());
    } catch (AuthenticationRejectedException rejected) {
      return new RpcOutcome.Rejected<>(rejected.code());
    }
  }

  private void appendEvent(UUID correlationId, UUID aggregateId, Integer aggregateVersion, EventToAppend event) {
    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(), event.messageType(), 1, MessageKind.EVENT.name(), aggregateId, aggregateVersion, correlationId, null,
            codec.writeValueAsString(event.payload()), Instant.now()));
  }
}
