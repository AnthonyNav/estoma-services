package com.estoma.washadministration.application.command;

import com.estoma.washadministration.application.command.CommandOutcome.Status;
import com.estoma.washadministration.application.port.AdministrativeAuditRepository;
import com.estoma.washadministration.application.port.AdministrativeAuditRepository.AuditRecord;
import com.estoma.washadministration.application.port.CommandIdempotencyRepository;
import com.estoma.washadministration.application.port.CommandIdempotencyRepository.IdempotencyRecord;
import com.estoma.washadministration.application.port.JsonCodec;
import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.OutboxRepository.OutboxMessage;
import com.estoma.washadministration.application.port.ProcessedMessageRepository;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Single local transaction for Inbox, idempotency, mutation, Result and Events. */
@Component
public class WashAdministrationCommandDispatcher {

  private static final String CONSUMER = "wash-administration.commands";
  private static final String PRODUCER = "wash-administration";

  private final ProcessedMessageRepository processedMessages;
  private final CommandIdempotencyRepository idempotency;
  private final AdministrativeAuditRepository audit;
  private final OutboxRepository outbox;
  private final JsonCodec json;
  private final WashAdministrationCommandHandler handler;

  public WashAdministrationCommandDispatcher(
      ProcessedMessageRepository processedMessages,
      CommandIdempotencyRepository idempotency,
      AdministrativeAuditRepository audit,
      OutboxRepository outbox,
      JsonCodec json,
      WashAdministrationCommandHandler handler) {
    this.processedMessages = processedMessages;
    this.idempotency = idempotency;
    this.audit = audit;
    this.outbox = outbox;
    this.json = json;
    this.handler = handler;
  }

  @Transactional
  public CommandOutcome dispatch(CommandRequest request) {
    validate(request);
    CommandMetadata metadata = request.metadata();
    Instant now = Instant.now();
    if (!processedMessages.registerIfAbsent(
        CONSUMER, metadata.messageId(), metadata.correlationId(), now)) {
      return CommandOutcome.duplicate();
    }

    String fingerprint = fingerprint(request);
    if (!idempotency.insertIfAbsent(
        metadata.producer(),
        metadata.operationType(),
        metadata.idempotencyKey(),
        fingerprint,
        now)) {
      IdempotencyRecord existing =
          idempotency
              .find(
                  metadata.producer(),
                  metadata.operationType(),
                  metadata.idempotencyKey())
              .orElseThrow(
                  () -> new IllegalStateException("idempotency record disappeared"));
      if (!fingerprint.equals(existing.requestFingerprint())) {
        return reject(
            request,
            "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
            existing.aggregateId(),
            now,
            false);
      }
      if ("IN_PROGRESS".equals(existing.status())) {
        throw new IllegalStateException(
            "idempotency record is still in progress after conflict");
      }
      Status status = storedStatus(existing);
      appendStoredResult(request, existing, status, now);
      return new CommandOutcome(
          status,
          existing.errorCode(),
          existing.aggregateId(),
          existing.aggregateVersion(),
          false);
    }

    Object savepoint =
        TransactionAspectSupport.currentTransactionStatus().createSavepoint();
    try {
      WashAdministrationCommandHandler.Change change = handler.handle(request, now);
      TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);

      if (change.auditReason() != null) {
        UUID accountId = metadata.actor().accountId();
        if (accountId == null) {
          throw new IllegalStateException(
              "an administrative audit requires an account actor");
        }
        audit.append(
            new AuditRecord(
                UUID.randomUUID(),
                metadata.operationId(),
                metadata.correlationId(),
                metadata.operationType(),
                change.aggregateId(),
                accountId,
                change.auditReason(),
                now));
      }

      Map<String, Object> resultData =
          map(
              "aggregateId",
              change.aggregateId(),
              "aggregateVersion",
              change.aggregateVersion());
      appendResult(
          request,
          Status.SUCCEEDED,
          null,
          change.aggregateId(),
          change.aggregateVersion(),
          resultData,
          now);
      for (WashAdministrationCommandHandler.Event event : change.events()) {
        appendEvent(request, event, now);
      }
      String resultJson = json.write(resultData);
      if (idempotency.markSucceeded(
              metadata.producer(),
              metadata.operationType(),
              metadata.idempotencyKey(),
              change.aggregateId(),
              change.aggregateVersion(),
              Status.SUCCEEDED.name(),
              resultJson,
              now)
          != 1) {
        throw new IllegalStateException("idempotency success transition failed");
      }
      return CommandOutcome.succeeded(
          change.aggregateId(), change.aggregateVersion());
    } catch (CommandRejectedException exception) {
      rollback(savepoint);
      return reject(
          request, exception.code(), exception.aggregateId(), now, true);
    }
  }

  private CommandOutcome reject(
      CommandRequest request,
      String code,
      UUID aggregateId,
      Instant now,
      boolean persistIdempotency) {
    CommandMetadata metadata = request.metadata();
    Map<String, Object> error = map("code", code);
    appendResult(
        request,
        Status.REJECTED,
        code,
        aggregateId,
        null,
        map("error", error),
        now);
    String resultJson = json.write(map("error", error));
    if (persistIdempotency
        && idempotency.markRejected(
                metadata.producer(),
                metadata.operationType(),
                metadata.idempotencyKey(),
                aggregateId,
                null,
                code,
                Status.REJECTED.name(),
                resultJson,
                now)
            != 1) {
      throw new IllegalStateException("idempotency rejection transition failed");
    }
    return CommandOutcome.rejected(code, aggregateId);
  }

  private void appendStoredResult(
      CommandRequest request,
      IdempotencyRecord existing,
      Status status,
      Instant now) {
    Object data =
        existing.resultDataJson() == null
            ? map("error", map("code", existing.errorCode()))
            : json.parse(existing.resultDataJson());
    appendResult(
        request,
        status,
        existing.errorCode(),
        existing.aggregateId(),
        existing.aggregateVersion(),
        data,
        now);
  }

  private void appendResult(
      CommandRequest request,
      Status status,
      String errorCode,
      UUID aggregateId,
      Integer aggregateVersion,
      Object data,
      Instant now) {
    CommandMetadata metadata = request.metadata();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", status.name());
    if (status == Status.SUCCEEDED) {
      payload.put("data", data);
    } else {
      payload.put("error", map("code", errorCode));
    }
    outbox.append(
        new OutboxMessage(
            UUID.randomUUID(),
            metadata.operationType() + "Result",
            1,
            "RESULT",
            PRODUCER,
            metadata.producer(),
            now,
            metadata.operationId(),
            metadata.idempotencyKey(),
            aggregateId,
            aggregateVersion,
            metadata.correlationId(),
            metadata.messageId(),
            json.write(metadata.actor()),
            json.write(payload),
            null));
  }

  private void appendEvent(
      CommandRequest request,
      WashAdministrationCommandHandler.Event event,
      Instant now) {
    CommandMetadata metadata = request.metadata();
    outbox.append(
        new OutboxMessage(
            UUID.randomUUID(),
            event.type(),
            1,
            "EVENT",
            PRODUCER,
            event.type(),
            now,
            metadata.operationId(),
            metadata.idempotencyKey(),
            event.aggregateId(),
            event.aggregateVersion(),
            metadata.correlationId(),
            metadata.messageId(),
            json.write(metadata.actor()),
            json.write(event.payload()),
            null));
  }

  private String fingerprint(CommandRequest request) {
    CommandMetadata metadata = request.metadata();
    return sha256(
        json.canonical(
            map(
                "operationType",
                metadata.operationType(),
                "producer",
                metadata.producer(),
                "actor",
                metadata.actor(),
                "payload",
                request.payload())));
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of()
          .formatHex(
              java.security.MessageDigest.getInstance("SHA-256")
                  .digest(
                      value.getBytes(
                          java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static void validate(CommandRequest request) {
    if (request == null || request.metadata() == null || request.payload() == null) {
      throw new CommandRejectedException("INVALID_MESSAGE");
    }
    CommandMetadata metadata = request.metadata();
    AdministrativeCommand payload = request.payload();
    if (metadata.messageId() == null
        || metadata.operationType() == null
        || metadata.messageVersion() != 1
        || metadata.producer() == null
        || metadata.correlationId() == null
        || metadata.operationId() == null
        || metadata.idempotencyKey() == null
        || metadata.idempotencyKey().isBlank()
        || metadata.idempotencyKey().length() > 200
        || metadata.actor() == null
        || !"bff".equals(metadata.producer())
        || !AdministrativeCommand.SUPPORTED_OPERATION_TYPES.contains(
            metadata.operationType())
        || !metadata.operationType().equals(payload.operationType())) {
      throw new CommandRejectedException("INVALID_MESSAGE");
    }
  }

  private static Status storedStatus(IdempotencyRecord existing) {
    String value =
        existing.resultStatus() == null ? existing.status() : existing.resultStatus();
    try {
      Status status = Status.valueOf(value);
      if (status == Status.DUPLICATE) {
        throw new IllegalStateException(
            "DUPLICATE is not a persisted terminal Result status");
      }
      return status;
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "unsupported persisted terminal Result status: " + value, exception);
    }
  }

  private static void rollback(Object savepoint) {
    TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
  }

  private static Map<String, Object> map(Object... values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2) {
      result.put((String) values[index], values[index + 1]);
    }
    return result;
  }
}
