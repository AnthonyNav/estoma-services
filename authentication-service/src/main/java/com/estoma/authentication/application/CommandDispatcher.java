package com.estoma.authentication.application;

import com.estoma.authentication.application.command.CommandHandler;
import com.estoma.authentication.application.command.CommandHandlerResult;
import com.estoma.authentication.application.port.InboxPort;
import com.estoma.authentication.application.port.CommandIdempotencyRepository;
import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.result.SucceededPayload;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
 * ({@link AuthenticationRejectedException}) is caught HERE, inside the
 * transaction, and turned into a durable {@code Rejected} result -- never
 * rethrown. If it were rethrown, the Consumer Inbox row inserted at the top
 * of this method would roll back too, and the command would be redelivered
 * forever once the messaging shell exists (commands.md's rejections are not
 * technical errors and must not reach a DLQ).
 *
 * <p>No RabbitMQ dependency: {@link #dispatch} takes an already-decoded
 * {@link MessageEnvelope} and returns a {@link CommandOutcome}, fully
 * testable against a real database without any messaging infrastructure --
 * see execution_plan.md's note on why business logic (steps 3-8) is built
 * before the messaging shell (step 9).
 */
@Component
public class CommandDispatcher {

  private final InboxPort inbox;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxPort outbox;
  private final AccountAuthorizationPolicy authorizationPolicy;
  private final EnvelopeJsonCodec codec;
  private final Map<String, CommandHandler> handlersByMessageType;

  CommandDispatcher(
      InboxPort inbox, CommandIdempotencyRepository idempotency, OutboxPort outbox, AccountAuthorizationPolicy authorizationPolicy, EnvelopeJsonCodec codec, List<CommandHandler> handlers) {
    this.inbox = inbox;
    this.idempotency = idempotency;
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

    CommandIdempotencyRepository.ClaimResult claim =
        idempotency.claim(envelope.producer(), envelope.messageType(), envelope.idempotencyKey(), fingerprint(envelope), envelope.aggregateId());
    if (claim instanceof CommandIdempotencyRepository.ClaimResult.Conflict) {
      appendResult(
          envelope,
          null,
          null,
          codec.writeValueAsString(ResultPayload.rejected("IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST")),
          "Rejected");
      return new CommandOutcome.Rejected(
          com.estoma.authentication.domain.RejectionCode.IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST);
    }
    if (claim instanceof CommandIdempotencyRepository.ClaimResult.Replay replay) {
      appendResult(envelope, replay.resultPayloadJson());
      return new CommandOutcome.Duplicate();
    }

    try {
      authorizationPolicy.check(envelope);
      CommandHandler handler = handlersByMessageType.get(envelope.messageType());
      if (handler == null) {
        throw new IllegalStateException("no handler registered for messageType " + envelope.messageType());
      }
      CommandHandlerResult result = handler.handle(envelope);
      appendSucceededResult(envelope, result);
      result.events().forEach(event -> appendEvent(envelope, result.aggregateId(), result.aggregateVersion(), event));
      return new CommandOutcome.Succeeded(result.aggregateVersion());
    } catch (AuthenticationRejectedException rejected) {
      appendRejectedResult(envelope, rejected);
      return new CommandOutcome.Rejected(rejected.code());
    }
  }

  private void appendSucceededResult(MessageEnvelope envelope, CommandHandlerResult result) {
    ResultPayload payload = ResultPayload.succeeded(new SucceededPayload(result.aggregateId(), result.aggregateVersion()));
    String payloadJson = codec.writeValueAsString(payload);
    idempotency.complete(envelope.producer(), envelope.messageType(), envelope.idempotencyKey(), payloadJson);
    appendResult(envelope, result.aggregateId(), result.aggregateVersion(), payloadJson, "Succeeded");
  }

  private void appendRejectedResult(MessageEnvelope envelope, AuthenticationRejectedException rejected) {
    ResultPayload payload = ResultPayload.rejected(rejected.code().name());
    String payloadJson = codec.writeValueAsString(payload);
    idempotency.complete(envelope.producer(), envelope.messageType(), envelope.idempotencyKey(), payloadJson);
    appendResult(envelope, null, null, payloadJson, "Rejected");
  }

  private void appendResult(MessageEnvelope envelope, String payloadJson) {
    String suffix = "SUCCEEDED".equals(codec.readTree(payloadJson).get("status").asString()) ? "Succeeded" : "Rejected";
    appendResult(envelope, null, null, payloadJson, suffix);
  }

  private void appendResult(MessageEnvelope envelope, UUID aggregateId, Integer aggregateVersion, String payloadJson, String suffix) {
    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(), envelope.messageType() + suffix, 1, MessageKind.RESULT.name(), aggregateId, aggregateVersion,
            envelope.correlationId(), envelope.messageId(), envelope.operationId(), envelope.idempotencyKey(), envelope.actor(), payloadJson, Instant.now()));
  }

  private void appendEvent(MessageEnvelope envelope, UUID aggregateId, Integer aggregateVersion, EventToAppend event) {
    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(), event.messageType(), 1, MessageKind.EVENT.name(), aggregateId, aggregateVersion,
            envelope.correlationId(), envelope.messageId(), envelope.operationId(), envelope.idempotencyKey(), envelope.actor(),
            codec.writeValueAsString(event.payload()), Instant.now()));
  }

  private String fingerprint(MessageEnvelope envelope) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest((envelope.messageType() + "\n" + codec.writeValueAsString(envelope.payload())).getBytes(StandardCharsets.UTF_8));
      return java.util.HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is unavailable", e);
    }
  }
}
