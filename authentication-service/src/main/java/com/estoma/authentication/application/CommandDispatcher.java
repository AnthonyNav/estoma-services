package com.estoma.authentication.application;

import com.estoma.authentication.application.command.CommandHandler;
import com.estoma.authentication.application.command.CommandHandlerResult;
import com.estoma.authentication.application.port.CommandIdempotencyRepository;
import com.estoma.authentication.application.port.InboxPort;
import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.result.ErrorPayload;
import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.result.ResultStatus;
import com.estoma.authentication.contracts.result.SucceededPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.observability.ConsumerNames;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Component
public class CommandDispatcher {

  private final InboxPort inbox;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxPort outbox;
  private final AccountAuthorizationPolicy authorizationPolicy;
  private final EnvelopeJsonCodec codec;
  private final Map<String, CommandHandler> handlersByMessageType;

  CommandDispatcher(
      InboxPort inbox,
      CommandIdempotencyRepository idempotency,
      OutboxPort outbox,
      AccountAuthorizationPolicy authorizationPolicy,
      EnvelopeJsonCodec codec,
      List<CommandHandler> handlers) {
    this.inbox = inbox;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.authorizationPolicy = authorizationPolicy;
    this.codec = codec;
    this.handlersByMessageType = handlers.stream()
        .collect(Collectors.toMap(CommandHandler::commandMessageType, Function.identity()));
  }

  @Transactional
  public CommandOutcome dispatch(MessageEnvelope envelope) {
    if (!inbox.tryInsert(ConsumerNames.COMMANDS, envelope.messageId(), envelope.correlationId())) {
      return new CommandOutcome.Duplicate();
    }

    try {
      authorizationPolicy.check(envelope);
    } catch (AuthenticationRejectedException rejected) {
      appendRejectedResult(envelope, rejected, false);
      return new CommandOutcome.Rejected(rejected.code());
    }

    CommandIdempotencyRepository.ClaimResult claim = idempotency.claim(
        envelope.producer(), envelope.messageType(), envelope.idempotencyKey(),
        fingerprint(envelope), envelope.aggregateId());

    if (claim instanceof CommandIdempotencyRepository.ClaimResult.Conflict) {
      AuthenticationRejectedException rejected = new AuthenticationRejectedException(
          RejectionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST,
          "idempotency key was reused for a different request");
      appendRejectedResult(envelope, rejected, false);
      return new CommandOutcome.Rejected(rejected.code());
    }
    if (claim instanceof CommandIdempotencyRepository.ClaimResult.Replay replay) {
      appendResult(envelope, null, null, replay.resultPayloadJson());
      return new CommandOutcome.Duplicate();
    }
    if (claim instanceof CommandIdempotencyRepository.ClaimResult.InProgress
        && !"academic".equals(envelope.producer())) {
      return new CommandOutcome.Duplicate();
    }

    CommandHandler handler = handlersByMessageType.get(envelope.messageType());
    if (handler == null) {
      throw new IllegalStateException("no handler registered for messageType " + envelope.messageType());
    }

    try {
      CommandHandlerResult result = handler.handle(envelope);
      ResultPayload payload = succeededPayload(envelope, result);
      String payloadJson = codec.writeValueAsString(payload);
      complete(envelope, payload.status(), payloadJson);
      appendResult(envelope, result.aggregateId(), result.aggregateVersion(), payloadJson);
      result.events().forEach(event -> appendEvent(envelope, result.aggregateId(), result.aggregateVersion(), event));
      return new CommandOutcome.Succeeded(result.aggregateVersion());
    } catch (DeferredAcademicCommandException deferred) {
      return new CommandOutcome.Deferred(deferred.pendingReason());
    } catch (AuthenticationRejectedException rejected) {
      appendRejectedResult(envelope, rejected, true);
      return new CommandOutcome.Rejected(rejected.code());
    }
  }

  private void appendRejectedResult(
      MessageEnvelope envelope, AuthenticationRejectedException rejected, boolean complete) {
    ResultPayload payload = rejectedPayload(envelope, rejected.code().name());
    String payloadJson = codec.writeValueAsString(payload);
    if (complete) {
      complete(envelope, payload.status(), payloadJson);
    }
    appendResult(envelope, null, null, payloadJson);
  }

  private ResultPayload succeededPayload(MessageEnvelope envelope, CommandHandlerResult result) {
    if (!"academic".equals(envelope.producer())) {
      return ResultPayload.succeeded(new SucceededPayload(result.aggregateId(), result.aggregateVersion()));
    }
    return ResultPayload.succeeded(importCoordinates(envelope));
  }

  private ResultPayload rejectedPayload(MessageEnvelope envelope, String code) {
    if (!"academic".equals(envelope.producer())) {
      return ResultPayload.rejected(code);
    }
    return new ResultPayload(ResultStatus.REJECTED, importCoordinates(envelope), ErrorPayload.of(code));
  }

  private Map<String, Object> importCoordinates(MessageEnvelope envelope) {
    JsonNode execution = envelope.payload().path("importExecution");
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("importId", requiredUuid(execution, "importId"));
    data.put("planItemId", requiredUuid(execution, "planItemId"));
    int generation = execution.path("executionGeneration").asInt(-1);
    if (generation < 1) {
      throw new IllegalArgumentException("invalid executionGeneration");
    }
    data.put("executionGeneration", generation);
    return data;
  }

  private void complete(MessageEnvelope envelope, ResultStatus status, String payloadJson) {
    idempotency.complete(
        envelope.producer(), envelope.messageType(), envelope.idempotencyKey(), status, payloadJson);
  }

  private void appendResult(
      MessageEnvelope envelope, UUID aggregateId, Integer aggregateVersion, String payloadJson) {
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), envelope.messageType() + "Result", 1, MessageKind.RESULT.name(),
        aggregateId, aggregateVersion, envelope.correlationId(), envelope.messageId(),
        envelope.operationId(), envelope.idempotencyKey(), envelope.actor(), envelope.producer(),
        payloadJson, Instant.now()));
  }

  private void appendEvent(
      MessageEnvelope envelope, UUID aggregateId, Integer aggregateVersion, EventToAppend event) {
    UUID eventAggregateId = event.inheritHandlerAggregate() ? aggregateId : event.aggregateId();
    Integer eventAggregateVersion = event.inheritHandlerAggregate() ? aggregateVersion : event.aggregateVersion();
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), event.messageType(), 1, MessageKind.EVENT.name(),
        eventAggregateId, eventAggregateVersion, envelope.correlationId(), envelope.messageId(),
        envelope.operationId(), envelope.idempotencyKey(), envelope.actor(), null,
        codec.writeValueAsString(event.payload()), Instant.now()));
  }

  private String fingerprint(MessageEnvelope envelope) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((envelope.messageType() + "\n" + codec.writeValueAsString(envelope.payload()))
              .getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }

  private static UUID requiredUuid(JsonNode node, String field) {
    try {
      return UUID.fromString(node.path(field).asString());
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("invalid " + field, exception);
    }
  }
}
