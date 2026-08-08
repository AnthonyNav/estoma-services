package com.estoma.users.application;

import com.estoma.users.application.handler.HandlerResult;
import com.estoma.users.application.handler.PersonCommandHandler;
import com.estoma.users.application.port.InboxPort;
import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.result.ResultPayload;
import com.estoma.users.contract.result.SucceededPayload;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.OutboxMetadata;
import com.estoma.users.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single transactional entry point for every durable command this
 * service handles. This is the ONLY {@code @Transactional} class in the
 * codebase (docs/system-contract.md §7): the transaction boundary is
 * auditable in one file, and handlers themselves stay free of
 * transaction management.
 *
 * <p>The critical property this class guarantees: a functional rejection
 * ({@link PersonRejectedException}) is caught HERE, inside the
 * transaction, and turned into a durable {@code Rejected} result — never
 * rethrown. If it were rethrown, the Consumer Inbox row inserted at the
 * top of this method would roll back too, the command would be
 * redelivered forever, and it would eventually reach the DLQ, which
 * directly contradicts commands.md:285 ("los rechazos funcionales no se
 * consideran errores técnicos").
 */
@Component
public class PersonCommandDispatcher {

  private static final Logger log = LoggerFactory.getLogger(PersonCommandDispatcher.class);

  private final InboxPort inbox;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;
  private final Map<String, PersonCommandHandler> handlersByMessageType;

  PersonCommandDispatcher(
      InboxPort inbox, OutboxPort outbox, EnvelopeJsonCodec codec, List<PersonCommandHandler> handlers) {
    this.inbox = inbox;
    this.outbox = outbox;
    this.codec = codec;
    this.handlersByMessageType =
        handlers.stream().collect(Collectors.toMap(PersonCommandHandler::commandMessageType, Function.identity()));
  }

  @Transactional
  public CommandOutcome dispatch(MessageEnvelope envelope) {
    // (a) Consumer Inbox — the FIRST statement in the transaction.
    boolean inserted = inbox.tryInsert(ConsumerNames.COMMANDS, envelope.messageId(), envelope.correlationId());
    if (!inserted) {
      return new CommandOutcome.Duplicate();
    }

    // (b) Functional authorization for the four global Person operations
    // is resolved upstream, by the producer, before publishing
    // (commands.md §8) — this class does not re-check it. EnvelopeValidator
    // already confirmed the producer is technically authorized for this
    // exact messageType (the producer/operation matrix) and that the
    // envelope carries a usable ACCOUNT actor.
    PersonCommandHandler handler = handlersByMessageType.get(envelope.messageType());
    if (handler == null) {
      // EnvelopeValidator already restricts messageType to a known
      // command before dispatch() is ever called; reaching here is a
      // wiring bug (a command registered in MessageTypes.COMMANDS with no
      // handler bean), not a message-quality problem.
      throw new IllegalStateException("no handler registered for messageType " + envelope.messageType());
    }

    try {
      requireAggregateIdMatchesPayload(envelope);
      HandlerResult result = handler.handle(envelope);
      appendSucceededResult(envelope, result);
      appendEvent(envelope, result);
      return new CommandOutcome.Succeeded(result.aggregateVersion());
    } catch (PersonRejectedException rejected) {
      // Caught INSIDE the transaction. THIS IS THE WHOLE POINT (see class
      // Javadoc). No rethrow: the Inbox row survives, the transaction
      // commits, the message gets ACKed, and it never reaches the DLQ.
      appendRejectedResult(envelope, rejected);
      return new CommandOutcome.Rejected(rejected.code());
    }
  }

  private void appendSucceededResult(MessageEnvelope envelope, HandlerResult result) {
    String resultType = envelope.messageType() + "Succeeded";
    ResultPayload payload =
        ResultPayload.succeeded(new SucceededPayload(result.aggregateId(), result.aggregateVersion()));
    OutboxMetadata metadata = new OutboxMetadata(envelope.producer(), envelope.operationId(), envelope.actor());

    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(),
            resultType,
            1,
            MessageKind.RESULT.name(),
            result.aggregateId(),
            result.aggregateVersion(),
            envelope.correlationId(),
            envelope.messageId(),
            codec.writeValueAsString(payload),
            codec.writeValueAsString(metadata),
            Instant.now()));
  }

  private void appendRejectedResult(MessageEnvelope envelope, PersonRejectedException rejected) {
    // The wire Result carries only error.code (messaging-architecture.md
    // §3) -- the human-readable detail is not part of the platform's
    // minimal Result shape, so it's logged here instead of published.
    log.info(
        "command {} rejected: code={}, detail={}",
        envelope.messageType(),
        rejected.code(),
        rejected.getMessage());
    String resultType = envelope.messageType() + "Rejected";
    ResultPayload payload = ResultPayload.rejected(rejected.code().name());
    OutboxMetadata metadata = new OutboxMetadata(envelope.producer(), envelope.operationId(), envelope.actor());

    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(),
            resultType,
            1,
            MessageKind.RESULT.name(),
            extractAttemptedPersonId(envelope),
            null, // no aggregate version change occurred
            envelope.correlationId(),
            envelope.messageId(),
            codec.writeValueAsString(payload),
            codec.writeValueAsString(metadata),
            Instant.now()));
  }

  private void appendEvent(MessageEnvelope envelope, HandlerResult result) {
    // No operationId on events (docs/system-contract.md §10 D6): it
    // tracks a client-visible operation, meaningless to an event consumer.
    OutboxMetadata metadata = new OutboxMetadata(null, null, envelope.actor());

    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(),
            result.eventMessageType(),
            1,
            MessageKind.EVENT.name(),
            result.aggregateId(),
            result.aggregateVersion(),
            envelope.correlationId(),
            envelope.messageId(),
            codec.writeValueAsString(result.eventPayload()),
            codec.writeValueAsString(metadata),
            Instant.now()));
  }

  /**
   * commands.md §2.1: for operations on an existing Person ({@code
   * UpdatePerson}, {@code ActivatePerson}, {@code DeactivatePerson}) the
   * envelope's {@code aggregateId} must equal the payload's {@code
   * personId}; {@code CreatePerson} carries the same equality by
   * construction (§4 — the producer sets both to the {@code personId} it
   * generates). Checked once here, for all four commands, rather than
   * per-handler: it's a structural consistency rule of the envelope/payload
   * pair, not a domain rule any single handler owns. Neither value being
   * present is not itself an error — this only rejects an actual mismatch.
   */
  private void requireAggregateIdMatchesPayload(MessageEnvelope envelope) {
    UUID aggregateId = envelope.aggregateId();
    UUID payloadPersonId = extractAttemptedPersonId(envelope);
    if (aggregateId != null && payloadPersonId != null && !aggregateId.equals(payloadPersonId)) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_MESSAGE, "envelope aggregateId does not match payload personId");
    }
  }

  /** All four commands share a {@code personId} payload field; best-effort extraction for a rejected result's aggregateId. */
  private UUID extractAttemptedPersonId(MessageEnvelope envelope) {
    try {
      String raw = envelope.payload().path("personId").asString(null);
      return raw == null ? null : UUID.fromString(raw);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
