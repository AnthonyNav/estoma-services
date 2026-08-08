package com.estoma.users.application;

import com.estoma.users.application.handler.HandlerResult;
import com.estoma.users.application.handler.PersonCommandHandler;
import com.estoma.users.application.port.IdempotencyPort;
import com.estoma.users.application.port.InboxPort;
import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.result.ResultPayload;
import com.estoma.users.contract.result.ResultStatus;
import com.estoma.users.contract.result.SucceededPayload;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.OutboxMetadata;
import com.estoma.users.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

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
 *
 * <p><b>Functional idempotency</b> (commands.md §9,
 * messaging-architecture.md §10.1) is layered on top of that, keyed by
 * {@code (producer, idempotencyKey)} rather than {@code messageId}: a
 * retry of the same intention under a brand-new {@code messageId} must
 * neither re-execute the handler nor go unanswered. {@link
 * IdempotencyPort#claim} is the single atomic decision point —
 * {@link #dispatch} branches into exactly one of three paths from its
 * result:
 *
 * <ol>
 *   <li>{@code claim.isNewClaim(messageId)} — this call inserted the
 *       claim: a genuinely new intention. Execute the handler, then
 *       resolve the claim to its terminal outcome.
 *   <li>an existing claim, but {@code messageType}/{@code
 *       requestFingerprint} disagree — the same key was reused for a
 *       different intention. Reject {@code INVALID_MESSAGE} <em>without
 *       touching the original claim's row</em>.
 *   <li>an existing claim for the same intention, already terminal (it
 *       can never be seen as still {@code IN_PROGRESS} here — see {@link
 *       com.estoma.users.persistence.JdbcIdempotencyRepository}'s
 *       Javadoc for why the claim statement's row lock rules that out) —
 *       reconstruct the original Result from the stored outcome, with no
 *       handler call, no domain mutation, no Event.
 * </ol>
 */
@Component
public class PersonCommandDispatcher {

  private static final Logger log = LoggerFactory.getLogger(PersonCommandDispatcher.class);

  private final InboxPort inbox;
  private final OutboxPort outbox;
  private final IdempotencyPort idempotency;
  private final EnvelopeJsonCodec codec;
  private final Map<String, PersonCommandHandler> handlersByMessageType;

  PersonCommandDispatcher(
      InboxPort inbox,
      OutboxPort outbox,
      IdempotencyPort idempotency,
      EnvelopeJsonCodec codec,
      List<PersonCommandHandler> handlers) {
    this.inbox = inbox;
    this.outbox = outbox;
    this.idempotency = idempotency;
    this.codec = codec;
    this.handlersByMessageType =
        handlers.stream().collect(Collectors.toMap(PersonCommandHandler::commandMessageType, Function.identity()));
  }

  @Transactional
  public CommandOutcome dispatch(MessageEnvelope envelope) {
    // (a) Consumer Inbox — the FIRST statement in the transaction. This
    // deduplicates DELIVERY of this exact messageId; it says nothing
    // about a retry of the same intention under a different messageId,
    // which functional idempotency (below) handles instead.
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

    UUID payloadPersonId = extractAttemptedPersonId(envelope);
    String requestFingerprint;
    try {
      requireAggregateIdMatchesPayload(envelope, payloadPersonId);
      requestFingerprint = buildRequestFingerprint(envelope);
    } catch (PersonRejectedException rejected) {
      // Structurally invalid or unparseable input -- deterministic, no
      // side effect to protect, never reaches the idempotency claim.
      appendRejectedResult(envelope, rejected);
      return new CommandOutcome.Rejected(rejected.code());
    }

    IdempotencyRecord claim =
        idempotency.claim(
            envelope.producer(),
            envelope.idempotencyKey(),
            envelope.messageType(),
            payloadPersonId,
            requestFingerprint,
            envelope.messageId(),
            Instant.now());

    if (!claim.isNewClaim(envelope.messageId())) {
      if (!claim.matchesIntention(envelope.messageType(), requestFingerprint)) {
        // Same idempotencyKey, different intention: reject, and leave the
        // original claim's row exactly as it was.
        PersonRejectedException invalidReuse =
            new PersonRejectedException(
                RejectionCode.INVALID_MESSAGE, "idempotencyKey reused for a different intention");
        appendRejectedResult(envelope, invalidReuse);
        return new CommandOutcome.Rejected(RejectionCode.INVALID_MESSAGE);
      }
      return reconstructOutcome(envelope, claim);
    }

    // We own this claim: a genuinely new intention.
    try {
      HandlerResult result = handler.handle(envelope);
      appendSucceededResult(envelope, result);
      appendEvent(envelope, result);
      idempotency.markSucceeded(
          envelope.producer(), envelope.idempotencyKey(), result.aggregateVersion(), Instant.now());
      return new CommandOutcome.Succeeded(result.aggregateVersion());
    } catch (PersonRejectedException rejected) {
      // Caught INSIDE the transaction. THIS IS THE WHOLE POINT (see class
      // Javadoc). No rethrow: the Inbox row survives, the transaction
      // commits, the message gets ACKed, and it never reaches the DLQ.
      appendRejectedResult(envelope, rejected);
      idempotency.markRejected(
          envelope.producer(), envelope.idempotencyKey(), rejected.code().name(), Instant.now());
      return new CommandOutcome.Rejected(rejected.code());
    }
  }

  /**
   * Re-derives a Result from a previously-completed claim — never from
   * current live state (re-reading the Person would answer with whatever
   * version it happens to be at NOW, not the version this exact
   * intention actually produced). No handler call, no domain mutation,
   * no Event: the original intention already ran to completion once.
   */
  private CommandOutcome reconstructOutcome(MessageEnvelope envelope, IdempotencyRecord claim) {
    if (claim.terminalStatus() == IdempotencyStatus.SUCCEEDED) {
      appendReconstructedResult(
          envelope, claim.aggregateId(), ResultPayload.succeeded(new SucceededPayload(
              claim.aggregateId(), claim.aggregateVersion())));
      return new CommandOutcome.Succeeded(claim.aggregateVersion());
    }
    RejectionCode code = RejectionCode.valueOf(claim.errorCode());
    appendReconstructedResult(envelope, claim.aggregateId(), ResultPayload.rejected(claim.errorCode()));
    return new CommandOutcome.Rejected(code);
  }

  private void appendReconstructedResult(MessageEnvelope envelope, UUID aggregateId, ResultPayload payload) {
    String resultSuffix = payload.status() == ResultStatus.SUCCEEDED ? "Succeeded" : "Rejected";
    String resultType = envelope.messageType() + resultSuffix;
    OutboxMetadata metadata = new OutboxMetadata(envelope.producer(), envelope.operationId(), envelope.actor());

    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(),
            resultType,
            1,
            MessageKind.RESULT.name(),
            aggregateId,
            null, // reconstructed rows never claim a fresh aggregateVersion of their own
            envelope.correlationId(),
            envelope.messageId(), // causationId: THIS retry's messageId
            codec.writeValueAsString(payload),
            codec.writeValueAsString(metadata),
            Instant.now()));
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
   * pair, not a domain rule any single handler owns.
   */
  private void requireAggregateIdMatchesPayload(MessageEnvelope envelope, UUID payloadPersonId) {
    UUID aggregateId = envelope.aggregateId();
    if (aggregateId == null || payloadPersonId == null || !aggregateId.equals(payloadPersonId)) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_MESSAGE, "envelope aggregateId does not match payload personId");
    }
  }

  /**
   * commands.md §9, §1.2.1: the functional intention's fingerprint, over
   * the fields the caller's own contract defines for this messageType, in
   * a fixed order — never delivery/tracing metadata (messageId,
   * operationId, correlationId, causationId, occurredAt), which can
   * legitimately differ between a command and its retry without changing
   * the intention. {@code institutionalEmail} is normalized the same way
   * the domain does (trim + lowercase) so a retry that only differs in
   * casing still fingerprints identically.
   */
  private String buildRequestFingerprint(MessageEnvelope envelope) {
    JsonNode p = envelope.payload();
    String messageType = envelope.messageType();
    return switch (messageType) {
      case MessageTypes.CREATE_PERSON -> RequestFingerprint.of(
          messageType,
          envelope.aggregateId().toString(),
          text(p, "personId"),
          text(p, "firstName"),
          text(p, "paternalSurname"),
          text(p, "maternalSurname"),
          normalizedEmail(text(p, "institutionalEmail")),
          text(p, "phone"));
      case MessageTypes.UPDATE_PERSON -> RequestFingerprint.of(
          messageType,
          envelope.aggregateId().toString(),
          text(p, "personId"),
          text(p, "expectedVersion"),
          text(p, "firstName"),
          text(p, "paternalSurname"),
          text(p, "maternalSurname"),
          normalizedEmail(text(p, "institutionalEmail")),
          text(p, "phone"));
      case MessageTypes.ACTIVATE_PERSON -> RequestFingerprint.of(
          messageType, envelope.aggregateId().toString(), text(p, "personId"), text(p, "expectedVersion"));
      case MessageTypes.DEACTIVATE_PERSON -> RequestFingerprint.of(
          messageType,
          envelope.aggregateId().toString(),
          text(p, "personId"),
          text(p, "expectedVersion"),
          text(p, "reasonCode"),
          text(p, "reasonDetail"));
      default -> throw new IllegalStateException("no fingerprint rule for messageType " + messageType);
    };
  }

  private static String normalizedEmail(String raw) {
    return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).asString(null);
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
