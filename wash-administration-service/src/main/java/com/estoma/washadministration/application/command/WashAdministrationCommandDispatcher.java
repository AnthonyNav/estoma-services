package com.estoma.washadministration.application.command;

import com.estoma.washadministration.application.port.CommandIdempotencyRepository;
import com.estoma.washadministration.application.port.CommandIdempotencyRepository.IdempotencyRecord;
import com.estoma.washadministration.application.port.AdministrativeAuditRepository;
import com.estoma.washadministration.application.port.AdministrativeAuditRepository.AuditRecord;
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
    if (!processedMessages.registerIfAbsent(CONSUMER, metadata.messageId(), metadata.correlationId(), now)) {
      return CommandOutcome.duplicate();
    }

    String fingerprint = fingerprint(request);
    if (!idempotency.insertIfAbsent(
        metadata.producer(), metadata.operationType(), metadata.idempotencyKey(), fingerprint, now)) {
      IdempotencyRecord existing = idempotency.find(
              metadata.producer(), metadata.operationType(), metadata.idempotencyKey())
          .orElseThrow(() -> new IllegalStateException("idempotency record disappeared"));
      if (!fingerprint.equals(existing.requestFingerprint())) {
        return reject(
            request,
            "IDEMPOTENCY_KEY_REUSED_WITH_DIFFERENT_REQUEST",
            existing.aggregateId(),
            now,
            false);
      }
      if ("IN_PROGRESS".equals(existing.status())) {
        throw new IllegalStateException("idempotency record is still in progress after conflict");
      }
      appendStoredResult(request, existing, now);
      return new CommandOutcome(
          existing.resultStatus(),
          existing.errorCode(),
          existing.aggregateId(),
          existing.aggregateVersion(),
          false);
    }

    Object savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
    try {
      WashAdministrationCommandHandler.Change change = handler.handle(request, now);
      TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);

      if (change.auditReason() != null) {
        UUID accountId = metadata.actor().accountId();
        if (accountId == null) {
          throw new IllegalStateException("an administrative audit requires an account actor");
        }
        audit.append(new AuditRecord(
            UUID.randomUUID(),
            metadata.operationId(),
            metadata.correlationId(),
            metadata.operationType(),
            change.aggregateId(),
            accountId,
            change.auditReason(),
            now));
      }

      Map<String, Object> resultData = map(
          "aggregateId", change.aggregateId(), "aggregateVersion", change.aggregateVersion());
      appendResult(request, "SUCCEEDED", null, change.aggregateId(), change.aggregateVersion(), resultData, now);
      for (WashAdministrationCommandHandler.Event event : change.events()) {
        appendEvent(request, event, now);
      }
      String resultJson = json.write(resultData);
      if (idempotency.markSucceeded(
              metadata.producer(), metadata.operationType(), metadata.idempotencyKey(),
              change.aggregateId(), change.aggregateVersion(), "SUCCEEDED", resultJson, now)
          != 1) {
        throw new IllegalStateException("idempotency success transition failed");
      }
      return CommandOutcome.succeeded(change.aggregateId(), change.aggregateVersion());
    } catch (CommandRejectedException exception) {
      rollback(savepoint);
      return reject(request, exception.code(), exception.aggregateId(), now, true);
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
    appendResult(request, "REJECTED", code, aggregateId, null, map("error", error), now);
    String resultJson = json.write(map("error", error));
    if (persistIdempotency
        && idempotency.markRejected(
            metadata.producer(), metadata.operationType(), metadata.idempotencyKey(),
            aggregateId, null, code,
            "REJECTED", resultJson, now)
            != 1) {
      throw new IllegalStateException("idempotency rejection transition failed");
    }
    return CommandOutcome.rejected(code, aggregateId);
  }

  private void appendStoredResult(CommandRequest request, IdempotencyRecord existing, Instant now) {
    String status = existing.resultStatus() == null ? existing.status() : existing.resultStatus();
    Object data = existing.resultDataJson() == null
        ? map("error", map("code", existing.errorCode()))
        : json.parse(existing.resultDataJson());
    appendResult(request, status, existing.errorCode(), existing.aggregateId(), existing.aggregateVersion(), data, now);
  }

  private void appendResult(
      CommandRequest request,
      String status,
      String errorCode,
      UUID aggregateId,
      Integer aggregateVersion,
      Object data,
      Instant now) {
    CommandMetadata metadata = request.metadata();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", status);
    if ("SUCCEEDED".equals(status)) {
      payload.put("data", data);
    } else {
      payload.put("error", map("code", errorCode));
    }
    outbox.append(new OutboxMessage(
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
        json.write(map("destination", metadata.producer()))));
  }

  private void appendEvent(
      CommandRequest request,
      WashAdministrationCommandHandler.Event event,
      Instant now) {
    CommandMetadata metadata = request.metadata();
    outbox.append(new OutboxMessage(
        UUID.randomUUID(),
        event.type(),
        1,
        "EVENT",
        PRODUCER,
        eventRoutingKey(event.type()),
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
    return sha256(json.canonical(json.tree(map(
        "operationType", metadata.operationType(),
        "producer", metadata.producer(),
        "actor", metadata.actor(),
        "payload", request.payload()))));
  }

  private static String sha256(String value) {
    try {
      return java.util.HexFormat.of().formatHex(
          java.security.MessageDigest.getInstance("SHA-256")
              .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String eventRoutingKey(String type) {
    return switch (type) {
      case "ServiceCalendarCreated" -> "wash-administration.service-calendar.created";
      case "ServiceCalendarUpdated" -> "wash-administration.service-calendar.updated";
      case "ServiceCalendarActivated" -> "wash-administration.service-calendar.activated";
      case "ServiceCalendarDeactivated" -> "wash-administration.service-calendar.deactivated";
      case "ServiceScheduleConfigured" -> "wash-administration.service-schedule.configured";
      case "ServiceScheduleUpdated" -> "wash-administration.service-schedule.updated";
      case "ServiceScheduleDeactivated" -> "wash-administration.service-schedule.deactivated";
      case "AdministrativeClosureScheduled" -> "wash-administration.administrative-closure.scheduled";
      case "AdministrativeClosureCancelled" -> "wash-administration.administrative-closure.cancelled";
      case "CabinRegistered" -> "wash-administration.cabin.registered";
      case "CabinUpdated" -> "wash-administration.cabin.updated";
      case "CabinActivated" -> "wash-administration.cabin.activated";
      case "CabinDeactivated" -> "wash-administration.cabin.deactivated";
      case "CabinRetired" -> "wash-administration.cabin.retired";
      case "TankRegistered" -> "wash-administration.tank.registered";
      case "TankUpdated" -> "wash-administration.tank.updated";
      case "TankActivated" -> "wash-administration.tank.activated";
      case "TankDeactivated" -> "wash-administration.tank.deactivated";
      case "TankRetired" -> "wash-administration.tank.retired";
      case "ResourcePriorityConfigured" -> "wash-administration.resource-priority.configured";
      default -> throw new IllegalArgumentException("unsupported event type: " + type);
    };
  }

  private static void validate(CommandRequest request) {
    if (request == null || request.metadata() == null || request.payload() == null
        || !request.payload().isObject()) {
      throw new CommandRejectedException("INVALID_MESSAGE");
    }
    CommandMetadata metadata = request.metadata();
    if (metadata.messageId() == null || metadata.operationType() == null
        || metadata.messageVersion() != 1 || metadata.producer() == null
        || metadata.correlationId() == null || metadata.operationId() == null
        || metadata.idempotencyKey() == null || metadata.idempotencyKey().isBlank()
        || metadata.idempotencyKey().length() > 200
        || metadata.actor() == null || !"bff".equals(metadata.producer())
        || !SUPPORTED.contains(metadata.operationType())) {
      throw new CommandRejectedException("INVALID_MESSAGE");
    }
  }

  private static final java.util.Set<String> SUPPORTED = java.util.Set.of(
      "CreateServiceCalendar", "UpdateServiceCalendar", "ActivateServiceCalendar", "DeactivateServiceCalendar",
      "ConfigureServiceSchedule", "UpdateServiceSchedule", "DeactivateServiceSchedule",
      "ScheduleAdministrativeClosure", "CancelAdministrativeClosure",
      "RegisterCabin", "UpdateCabin", "ActivateCabin", "DeactivateCabin", "RetireCabin",
      "RegisterTank", "UpdateTank", "ActivateTank", "DeactivateTank", "RetireTank");

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
