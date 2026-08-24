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
import com.estoma.users.contract.result.SucceededPayload;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.OutboxMetadata;
import com.estoma.users.observability.ConsumerNames;
import java.time.Instant;
import java.util.LinkedHashMap;
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

/** Transactional durable-command entry point for Person mutations. */
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
    this.handlersByMessageType = handlers.stream()
        .collect(Collectors.toMap(PersonCommandHandler::commandMessageType, Function.identity()));
  }

  @Transactional
  public CommandOutcome dispatch(MessageEnvelope envelope) {
    if (!inbox.tryInsert(ConsumerNames.COMMANDS, envelope.messageId(), envelope.correlationId())) {
      return new CommandOutcome.Duplicate();
    }

    PersonCommandHandler handler = handlersByMessageType.get(envelope.messageType());
    if (handler == null) {
      throw new IllegalStateException("no handler registered for messageType " + envelope.messageType());
    }

    UUID payloadPersonId = extractAttemptedPersonId(envelope);
    String requestFingerprint;
    try {
      requireAggregateIdMatchesPayload(envelope, payloadPersonId);
      requestFingerprint = buildRequestFingerprint(envelope);
    } catch (PersonRejectedException rejected) {
      appendRejectedResult(envelope, rejected);
      return new CommandOutcome.Rejected(rejected.code());
    }

    IdempotencyRecord claim = idempotency.claim(
        envelope.producer(), envelope.idempotencyKey(), envelope.messageType(), payloadPersonId,
        requestFingerprint, envelope.messageId(), Instant.now());

    if (!claim.isNewClaim(envelope.messageId())) {
      if (!claim.matchesIntention(envelope.messageType(), requestFingerprint)) {
        PersonRejectedException invalidReuse = new PersonRejectedException(
            RejectionCode.INVALID_MESSAGE, "idempotencyKey reused for a different intention");
        appendRejectedResult(envelope, invalidReuse);
        return new CommandOutcome.Rejected(RejectionCode.INVALID_MESSAGE);
      }
      return reconstructOutcome(envelope, claim);
    }

    try {
      HandlerResult result = handler.handle(envelope);
      appendSucceededResult(envelope, result);
      appendEvent(envelope, result);
      idempotency.markSucceeded(
          envelope.producer(), envelope.idempotencyKey(), result.aggregateVersion(), Instant.now());
      return new CommandOutcome.Succeeded(result.aggregateVersion());
    } catch (PersonRejectedException rejected) {
      appendRejectedResult(envelope, rejected);
      idempotency.markRejected(
          envelope.producer(), envelope.idempotencyKey(), rejected.code().name(), Instant.now());
      return new CommandOutcome.Rejected(rejected.code());
    }
  }

  /**
   * Persists the terminal technical outcome for a valid coordinated Academic Command.
   * This runs in a fresh transaction after the failed business transaction rolled back.
   */
  @Transactional
  public void fail(MessageEnvelope envelope, String errorCode) {
    if (!"academic".equals(envelope.producer())) {
      throw new IllegalArgumentException("terminal coordinated Result is only defined for academic Commands");
    }
    JsonNode execution = envelope.payload().path("importExecution");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("importId", requiredUuid(execution, "importId"));
    data.put("planItemId", requiredUuid(execution, "planItemId"));
    int generation = execution.path("executionGeneration").asInt(-1);
    if (generation < 1) {
      throw new IllegalStateException("academic command is missing executionGeneration");
    }
    data.put("executionGeneration", generation);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", "FAILED");
    payload.put("data", data);
    payload.put("error", Map.of("code", errorCode));

    OutboxMetadata metadata = new OutboxMetadata(
        envelope.producer(), envelope.operationId(), envelope.idempotencyKey(), envelope.actor());
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), envelope.messageType() + "Result", 1, MessageKind.RESULT.name(),
        extractAttemptedPersonId(envelope), null, envelope.correlationId(), envelope.messageId(),
        codec.writeValueAsString(payload), codec.writeValueAsString(metadata), Instant.now()));
  }

  private CommandOutcome reconstructOutcome(MessageEnvelope envelope, IdempotencyRecord claim) {
    if (claim.terminalStatus() == IdempotencyStatus.SUCCEEDED) {
      appendTerminalResult(
          envelope,
          claim.aggregateId(),
          claim.aggregateVersion(),
          true,
          null,
          ResultPayload.succeeded(new SucceededPayload(claim.aggregateId(), claim.aggregateVersion())));
      return new CommandOutcome.Succeeded(claim.aggregateVersion());
    }
    RejectionCode code = RejectionCode.valueOf(claim.errorCode());
    appendTerminalResult(
        envelope,
        claim.aggregateId(),
        null,
        false,
        claim.errorCode(),
        ResultPayload.rejected(claim.errorCode()));
    return new CommandOutcome.Rejected(code);
  }

  private void appendSucceededResult(MessageEnvelope envelope, HandlerResult result) {
    appendTerminalResult(
        envelope,
        result.aggregateId(),
        result.aggregateVersion(),
        true,
        null,
        ResultPayload.succeeded(new SucceededPayload(result.aggregateId(), result.aggregateVersion())));
  }

  private void appendRejectedResult(MessageEnvelope envelope, PersonRejectedException rejected) {
    log.info("command {} rejected: code={}, detail={}",
        envelope.messageType(), rejected.code(), rejected.getMessage());
    appendTerminalResult(
        envelope,
        extractAttemptedPersonId(envelope),
        null,
        false,
        rejected.code().name(),
        ResultPayload.rejected(rejected.code().name()));
  }

  private void appendTerminalResult(
      MessageEnvelope envelope,
      UUID aggregateId,
      Integer aggregateVersion,
      boolean succeeded,
      String errorCode,
      ResultPayload legacyPayload) {
    boolean academic = "academic".equals(envelope.producer());
    String resultType = academic
        ? envelope.messageType() + "Result"
        : envelope.messageType() + (succeeded ? "Succeeded" : "Rejected");
    Object payload = academic ? academicResultPayload(envelope, succeeded, errorCode) : legacyPayload;
    OutboxMetadata metadata = academic
        ? new OutboxMetadata(
            envelope.producer(), envelope.operationId(), envelope.idempotencyKey(), envelope.actor())
        : new OutboxMetadata(envelope.producer(), envelope.operationId(), envelope.actor());

    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), resultType, 1, MessageKind.RESULT.name(), aggregateId, aggregateVersion,
        envelope.correlationId(), envelope.messageId(), codec.writeValueAsString(payload),
        codec.writeValueAsString(metadata), Instant.now()));
  }

  private Map<String, Object> academicResultPayload(
      MessageEnvelope envelope, boolean succeeded, String errorCode) {
    JsonNode execution = envelope.payload().path("importExecution");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("importId", requiredUuid(execution, "importId"));
    data.put("planItemId", requiredUuid(execution, "planItemId"));
    int generation = execution.path("executionGeneration").asInt(-1);
    if (generation < 1) {
      throw new IllegalStateException("academic command is missing executionGeneration");
    }
    data.put("executionGeneration", generation);

    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", succeeded ? "SUCCEEDED" : "REJECTED");
    payload.put("data", data);
    if (!succeeded) {
      payload.put("error", Map.of("code", errorCode));
    }
    return payload;
  }

  private void appendEvent(MessageEnvelope envelope, HandlerResult result) {
    OutboxMetadata metadata = new OutboxMetadata(null, null, envelope.actor());
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), result.eventMessageType(), 1, MessageKind.EVENT.name(),
        result.aggregateId(), result.aggregateVersion(), envelope.correlationId(), envelope.messageId(),
        codec.writeValueAsString(result.eventPayload()), codec.writeValueAsString(metadata), Instant.now()));
  }

  private void requireAggregateIdMatchesPayload(MessageEnvelope envelope, UUID payloadPersonId) {
    UUID aggregateId = envelope.aggregateId();
    if (aggregateId == null || payloadPersonId == null || !aggregateId.equals(payloadPersonId)) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_MESSAGE, "envelope aggregateId does not match payload personId");
    }
  }

  private String buildRequestFingerprint(MessageEnvelope envelope) {
    JsonNode p = envelope.payload();
    String messageType = envelope.messageType();
    String base = switch (messageType) {
      case MessageTypes.CREATE_PERSON -> RequestFingerprint.of(
          messageType, envelope.aggregateId().toString(), text(p, "personId"), text(p, "firstName"),
          text(p, "paternalSurname"), text(p, "maternalSurname"),
          normalizedEmail(text(p, "institutionalEmail")), text(p, "phone"));
      case MessageTypes.UPDATE_PERSON -> RequestFingerprint.of(
          messageType, envelope.aggregateId().toString(), text(p, "personId"), text(p, "expectedVersion"),
          text(p, "firstName"), text(p, "paternalSurname"), text(p, "maternalSurname"),
          normalizedEmail(text(p, "institutionalEmail")), text(p, "phone"));
      case MessageTypes.ACTIVATE_PERSON -> RequestFingerprint.of(
          messageType, envelope.aggregateId().toString(), text(p, "personId"), text(p, "expectedVersion"));
      case MessageTypes.DEACTIVATE_PERSON -> RequestFingerprint.of(
          messageType, envelope.aggregateId().toString(), text(p, "personId"), text(p, "expectedVersion"),
          text(p, "reasonCode"), text(p, "reasonDetail"));
      default -> throw new IllegalStateException("no fingerprint rule for messageType " + messageType);
    };
    if (!"academic".equals(envelope.producer())) {
      return base;
    }
    JsonNode execution = p.path("importExecution");
    return RequestFingerprint.of(
        base,
        text(execution, "importId"),
        text(execution, "planItemId"),
        text(execution, "executionGeneration"));
  }

  private static UUID requiredUuid(JsonNode node, String field) {
    try {
      return UUID.fromString(node.path(field).asString());
    } catch (RuntimeException exception) {
      throw new IllegalStateException("academic command is missing valid " + field, exception);
    }
  }

  private static String normalizedEmail(String raw) {
    return raw == null ? null : raw.trim().toLowerCase(Locale.ROOT);
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).asString(null);
  }

  private UUID extractAttemptedPersonId(MessageEnvelope envelope) {
    try {
      String raw = envelope.payload().path("personId").asString(null);
      return raw == null ? null : UUID.fromString(raw);
    } catch (RuntimeException e) {
      return null;
    }
  }
}
