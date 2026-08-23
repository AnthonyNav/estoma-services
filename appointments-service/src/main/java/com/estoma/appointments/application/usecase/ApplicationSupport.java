package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.ActorContext;
import com.estoma.appointments.application.model.CommandMetadata;
import com.estoma.appointments.application.model.ExecutionEvent;
import com.estoma.appointments.application.model.OutboxIntent;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Shared application-boundary mechanics; it contains no transport concerns. */
final class ApplicationSupport {
  private ApplicationSupport() {}

  static String fingerprint(Object... values) {
    StringBuilder canonical = new StringBuilder();
    for (Object value : values) canonical.append(value == null ? "<null>" : value).append('|');
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required", exception);
    }
  }

  static Optional<ApplicationResult> claim(CommandIdempotencyRepository repository,
      CommandMetadata metadata, String operation, String fingerprint, Instant now) {
    CommandIdempotencyRepository.IdempotencyDecision decision = repository.claim(
        operation, metadata.idempotencyKey(), fingerprint, metadata.messageId(), now);
    if (decision.conflict()) {
      return Optional.of(ApplicationResult.rejected(
          "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST", "idempotency key belongs to another request"));
    }
    if (decision.replay()) {
      if (decision.record() != null && "COMPLETED".equals(decision.record().status())) {
        return Optional.of(ApplicationResult.duplicate());
      }
      return Optional.of(ApplicationResult.deferred("COMMAND_IN_PROGRESS", "the same command is already being processed", null));
    }
    return Optional.empty();
  }

  static void complete(CommandIdempotencyRepository repository, CommandMetadata metadata,
      String operation, ApplicationResult result, Instant now) {
    String json = resultJson(result);
    if (!repository.complete(operation, metadata.idempotencyKey(), json, now)) {
      throw new IllegalStateException("idempotency record disappeared before completion");
    }
  }

  static void appendResult(OutboxRepository outbox, CommandMetadata metadata,
      ApplicationResult result, Instant now) {
    append(outbox, new OutboxIntent("AppointmentCommandResult", "RESULT", result.aggregateId(),
        result.aggregateVersion(), metadata.correlationId(), metadata.causationId(), resultJson(result), now));
  }

  static void appendEvent(OutboxRepository outbox, CommandMetadata metadata, String type,
      UUID aggregateId, long aggregateVersion, String payload, Instant now) {
    append(outbox, new OutboxIntent(type, "EVENT", aggregateId, aggregateVersion,
        metadata.correlationId(), metadata.causationId(), payload, now));
  }

  static CommandMetadata inboundMetadata(ExecutionEvent event, Instant now) {
    return new CommandMetadata(event.messageId(), event.sourceEventId().toString(), event.messageId(),
        event.sourceEventId(), new ActorContext(new UUID(0L, 0L), "SERVICE", "appointments"), now);
  }

  static void append(OutboxRepository outbox, OutboxIntent intent) {
    Instant occurred = Objects.requireNonNull(intent.occurredAt(), "occurredAt");
    outbox.append(new OutboxRepository.OutboxMessage(UUID.randomUUID(), intent.messageType(), 1,
        intent.messageKind(), intent.aggregateId(), intent.aggregateVersion(), intent.correlationId(),
        intent.causationId(), intent.payloadJson() == null ? "{}" : intent.payloadJson(), occurred,
        occurred, "PENDING", 0, null, null));
  }

  static String resultJson(ApplicationResult result) {
    return "{\"status\":\"" + escape(result.status().name()) + "\",\"code\":\""
        + escape(result.code()) + "\",\"message\":\"" + escape(result.message()) + "\""
        + (result.aggregateId() == null ? "" : ",\"aggregateId\":\"" + result.aggregateId() + "\"")
        + ",\"aggregateVersion\":" + result.aggregateVersion() + "}";
  }

  static String escape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
  }
}
