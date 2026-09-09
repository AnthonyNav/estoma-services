package com.estoma.platformbff.application.commands;

import com.estoma.platformbff.application.port.ClientOperationRepository;
import com.estoma.platformbff.application.port.IdempotencyRepository;
import com.estoma.platformbff.application.port.MessageSerializer;
import com.estoma.platformbff.application.port.OutboxRepository;
import com.estoma.platformbff.application.port.RequestFingerprintPort;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.CommandSubmission;
import com.estoma.platformbff.contracts.messaging.CommandSubmissionResult;
import com.estoma.platformbff.contracts.messaging.OwnerRoute;
import com.estoma.platformbff.domain.idempotency.IdempotencyRecord;
import com.estoma.platformbff.domain.messaging.OutboxMessage;
import com.estoma.platformbff.domain.messaging.OutboxStatus;
import com.estoma.platformbff.domain.operation.ClientOperation;
import com.estoma.platformbff.domain.operation.OperationStatus;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CommandGatewayService {
  private static final String COMMANDS_EXCHANGE = "platform.commands";
  private static final Set<String> EXACT_ACCOUNT_ACTOR_OWNERS =
      Set.of("authorization", "wash-administration", "appointments");

  private final CommandRegistry registry;
  private final ClientOperationRepository operations;
  private final IdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final MessageSerializer serializer;
  private final RequestFingerprintPort fingerprints;

  public CommandGatewayService(CommandRegistry registry, ClientOperationRepository operations,
      IdempotencyRepository idempotency, OutboxRepository outbox, MessageSerializer serializer,
      RequestFingerprintPort fingerprints) {
    this.registry = registry;
    this.operations = operations;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.serializer = serializer;
    this.fingerprints = fingerprints;
  }

  @Transactional
  public CommandSubmissionResult submit(CommandSubmission submission) {
    validateShape(submission);
    OwnerRoute route = registry.require(submission.messageType());
    ActorContext actor = submission.actor() == null ? ActorContext.anonymous() : submission.actor();
    validateActor(submission, route, actor);

    var ownerPayload = fingerprints.normalize(
        submission.publicOperation(), submission.messageType(), submission.payloadJson(),
        submission.aggregateId(), submission.aggregateVersion(), actor);
    var idempotencyIntent = fingerprints.normalize(
        submission.publicOperation(), submission.messageType(), submission.idempotencyPayloadJson(),
        submission.idempotencyAggregateId(), submission.idempotencyAggregateVersion(), actor);
    String fingerprint = idempotencyIntent.fingerprint();

    var existing = idempotency.findByLogicalScope(
        submission.actorAccountId(), submission.publicOperation(), submission.idempotencyKey());
    if (existing.isPresent()) {
      return resolveExisting(existing.get(), fingerprint);
    }

    // Return the same timestamp precision that PostgreSQL persists for retries.
    Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
    UUID operationId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    var operation = new ClientOperation(
        operationId,
        submission.actorAccountId(),
        submission.publicOperation(),
        submission.messageType(),
        route.logicalComponentId(),
        route.routingKey(),
        correlationId,
        submission.idempotencyKey(),
        OperationStatus.PENDING,
        null,
        null,
        null,
        now,
        now,
        null,
        null);
    operations.insert(operation);

    var record = new IdempotencyRecord(
        scopeKey(submission),
        submission.actorAccountId(),
        submission.publicOperation(),
        submission.idempotencyKey(),
        fingerprint,
        operationId,
        now,
        null);

    if (!idempotency.insertIfAbsent(record)) {
      operations.deletePending(operationId);
      IdempotencyRecord winner = idempotency.findByLogicalScope(
              submission.actorAccountId(), submission.publicOperation(), submission.idempotencyKey())
          .orElseThrow(() -> new IllegalStateException(
              "idempotency conflict did not expose the winning logical scope"));
      return resolveExisting(winner, fingerprint);
    }

    UUID commandMessageId = UUID.randomUUID();
    outbox.insert(new OutboxMessage(
        commandMessageId,
        "COMMAND",
        submission.messageType(),
        route.commandMessageVersion(),
        COMMANDS_EXCHANGE,
        route.routingKey(),
        operationId,
        correlationId,
        null,
        submission.aggregateId(),
        submission.aggregateVersion(),
        submission.idempotencyKey(),
        ownerActorJson(route, actor),
        ownerPayload.canonicalPayloadJson(),
        OutboxStatus.PENDING,
        null,
        null,
        0,
        null,
        null,
        null,
        now,
        now));

    return new CommandSubmissionResult(
        operationId, OperationStatus.PENDING.name(), "/api/v1/operations/" + operationId, now, false);
  }

  private String ownerActorJson(OwnerRoute route, ActorContext actor) {
    if (EXACT_ACCOUNT_ACTOR_OWNERS.contains(route.logicalComponentId())
        && "ACCOUNT".equals(actor.type())) {
      return serializer.write(Map.of("type", actor.type(), "accountId", actor.accountId()));
    }
    return serializer.write(actor);
  }

  private CommandSubmissionResult resolveExisting(IdempotencyRecord record, String fingerprint) {
    if (!record.requestFingerprint().equals(fingerprint)) {
      throw new IdempotencyConflictException("Idempotency-Key was reused with a different request");
    }
    ClientOperation operation = operations.findById(record.operationId())
        .orElseThrow(() -> new IllegalStateException(
            "idempotency record references a missing client operation"));
    return new CommandSubmissionResult(
        operation.operationId(), operation.status().name(),
        "/api/v1/operations/" + operation.operationId(), operation.createdAt(), true);
  }

  private static void validateShape(CommandSubmission submission) {
    if (submission == null || isBlank(submission.payloadJson())
        || isBlank(submission.idempotencyPayloadJson())) {
      throw new IllegalArgumentException("command submission and payload are required");
    }
    if (isBlank(submission.publicOperation()) || submission.publicOperation().length() > 160) {
      throw new IllegalArgumentException("public operation is required and must fit the V1 contract");
    }
    if (isBlank(submission.messageType()) || submission.messageType().length() > 160) {
      throw new IllegalArgumentException("message type is required and must fit the V1 contract");
    }
    if (isBlank(submission.idempotencyKey()) || submission.idempotencyKey().length() > 200) {
      throw new IllegalArgumentException("Idempotency-Key is required and must fit the V1 contract");
    }
    if (submission.aggregateVersion() != null && submission.aggregateVersion() < 0) {
      throw new IllegalArgumentException("aggregateVersion cannot be negative");
    }
    if (submission.idempotencyAggregateVersion() != null
        && submission.idempotencyAggregateVersion() < 0) {
      throw new IllegalArgumentException("idempotencyAggregateVersion cannot be negative");
    }
  }

  private static void validateActor(
      CommandSubmission submission, OwnerRoute route, ActorContext actor) {
    switch (route.actorMode()) {
      case ANONYMOUS -> {
        if (submission.actorAccountId() != null
            || !"ANONYMOUS".equals(actor.type())
            || actor.accountId() != null
            || actor.service() != null
            || !"PUBLIC".equals(actor.scope())
            || actor.systemId() != null
            || submission.aggregateId() != null) {
          throw new IllegalArgumentException("command requires canonical anonymous PUBLIC actor");
        }
      }
      case ACCOUNT -> {
        if (submission.actorAccountId() == null
            || !"ACCOUNT".equals(actor.type())
            || !submission.actorAccountId().equals(actor.accountId())
            || actor.service() != null
            || isBlank(actor.scope())) {
          throw new IllegalArgumentException("command requires matching ACCOUNT actor context");
        }
      }
    }
  }

  private static String scopeKey(CommandSubmission submission) {
    String actor = submission.actorAccountId() == null
        ? "anonymous"
        : submission.actorAccountId().toString();
    return actor
        + "|" + submission.publicOperation().length() + ":" + submission.publicOperation()
        + "|" + submission.idempotencyKey().length() + ":" + submission.idempotencyKey();
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }

  public static class IdempotencyConflictException extends RuntimeException {
    public IdempotencyConflictException(String message) {
      super(message);
    }
  }
}
