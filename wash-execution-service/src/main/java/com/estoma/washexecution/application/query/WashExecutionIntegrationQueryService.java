package com.estoma.washexecution.application.query;

import com.estoma.washexecution.application.port.JsonCodec;
import com.estoma.washexecution.application.port.OutboxRepository;
import com.estoma.washexecution.application.port.WashExecutionIntegrationQueryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class WashExecutionIntegrationQueryService {
  private static final String PRODUCER = "wash-execution";
  private final WashExecutionIntegrationQueryRepository repository;
  private final OutboxRepository outbox;
  private final JsonCodec json;
  private final Clock clock;

  public WashExecutionIntegrationQueryService(
      WashExecutionIntegrationQueryRepository repository,
      OutboxRepository outbox,
      JsonCodec json,
      Clock clock) {
    this.repository = repository;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
  }

  public void respond(IntegrationQueryMetadata metadata, WashExecutionIntegrationQuery query) {
    Map<String, Object> payload = new LinkedHashMap<>();
    try {
      requireRequester(metadata.producer());
      payload.put("status", "SUCCEEDED");
      if (query instanceof WashExecutionIntegrationQuery.GetContext get) {
        if (get.appointmentId() == null) throw new QueryRejected("INVALID_APPOINTMENT_ID");
        payload.put("data", entity(repository.get(get.appointmentId())));
      } else if (query instanceof WashExecutionIntegrationQuery.ListPage page) {
        if (page.serviceDate() == null) throw new QueryRejected("INVALID_SERVICE_DATE");
        int size = page.pageSize() == null ? 100 : page.pageSize();
        if (size < 1 || size > 200) throw new QueryRejected("INVALID_PAGE_SIZE");
        validatePageToken(page.pageToken());
        payload.put("data", page(repository.list(page.serviceDate(), size, page.pageToken())));
      } else {
        throw new QueryRejected("UNSUPPORTED_QUERY");
      }
    } catch (QueryRejected rejection) {
      payload.clear();
      payload.put("status", "REJECTED");
      payload.put("error", Map.of("code", rejection.code));
    }
    append(metadata, payload);
  }

  public void respondRejected(IntegrationQueryMetadata metadata, String code) {
    append(metadata, new LinkedHashMap<>(Map.of(
        "status", "REJECTED",
        "error", Map.of("code", code == null || code.isBlank() ? "INVALID_QUERY" : code))));
  }

  private void append(IntegrationQueryMetadata metadata, Map<String, Object> payload) {
    Instant now = clock.instant();
    outbox.append(new OutboxRepository.OutboxMessage(
        UUID.randomUUID(),
        metadata.messageType() + "Result",
        1,
        "RESULT",
        PRODUCER,
        metadata.producer(),
        now,
        metadata.operationId(),
        null,
        null,
        null,
        metadata.correlationId(),
        metadata.messageId(),
        json.write(metadata.actor()),
        json.write(payload)));
  }

  private static void requireRequester(String producer) {
    if (!"appointments".equals(producer)) {
      throw new QueryRejected("QUERY_PRODUCER_NOT_ALLOWED");
    }
  }

  private static void validatePageToken(String pageToken) {
    if (pageToken == null || pageToken.isBlank()) return;
    try {
      UUID.fromString(pageToken);
    } catch (IllegalArgumentException exception) {
      throw new QueryRejected("INVALID_PAGE_TOKEN");
    }
  }

  private static Map<String, Object> entity(WashExecutionIntegrationQueryRepository.EntityContext value) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("appointmentId", value.appointmentId());
    data.put("washExecution", execution(value.washExecution()));
    data.put("resourceUnavailabilities", unavailabilities(value.resourceUnavailabilities()));
    data.put("cleaningPeriods", cleanings(value.cleaningPeriods()));
    return data;
  }

  private static Map<String, Object> page(WashExecutionIntegrationQueryRepository.Page value) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("snapshotGeneratedAt", value.snapshotGeneratedAt());
    data.put("serviceDate", value.serviceDate());
    data.put("executions", value.executions().stream()
        .map(WashExecutionIntegrationQueryService::execution).toList());
    data.put("resourceUnavailabilities", unavailabilities(value.resourceUnavailabilities()));
    data.put("cleaningPeriods", cleanings(value.cleaningPeriods()));
    if (value.nextPageToken() != null) data.put("nextPageToken", value.nextPageToken());
    return data;
  }

  private static Map<String, Object> execution(
      WashExecutionIntegrationQueryRepository.ExecutionSnapshot execution) {
    if (execution == null) return null;
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("washExecutionId", execution.washExecutionId());
    value.put("appointmentId", execution.appointmentId());
    value.put("appointmentType", execution.appointmentType());
    value.put("status", execution.status());
    value.put("arrivedAt", execution.arrivedAt());
    if (execution.startedAt() != null) value.put("startedAt", execution.startedAt());
    if (execution.entryAuthorizedAt() != null) value.put("entryAuthorizedAt", execution.entryAuthorizedAt());
    if (execution.completedAt() != null) value.put("completedAt", execution.completedAt());
    if (execution.cancelledAt() != null) value.put("cancelledAt", execution.cancelledAt());
    if (execution.cancellationType() != null) value.put("cancellationType", execution.cancellationType());
    value.put("version", execution.version());
    return value;
  }

  private static List<Map<String, Object>> unavailabilities(
      List<WashExecutionIntegrationQueryRepository.UnavailabilitySnapshot> values) {
    List<Map<String, Object>> output = new ArrayList<>();
    for (var snapshot : values) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("resourceUnavailabilityId", snapshot.resourceUnavailabilityId());
      if (snapshot.cabinId() != null) value.put("cabinId", snapshot.cabinId());
      if (snapshot.tankId() != null) value.put("tankId", snapshot.tankId());
      value.put("disabledAt", snapshot.disabledAt());
      if (snapshot.restoredAt() != null) value.put("restoredAt", snapshot.restoredAt());
      value.put("version", snapshot.version());
      output.add(value);
    }
    return output;
  }

  private static List<Map<String, Object>> cleanings(
      List<WashExecutionIntegrationQueryRepository.CleaningSnapshot> values) {
    List<Map<String, Object>> output = new ArrayList<>();
    for (var snapshot : values) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("cleaningPeriodId", snapshot.cleaningPeriodId());
      value.put("washExecutionId", snapshot.washExecutionId());
      value.put("resourceAssignmentId", snapshot.resourceAssignmentId());
      value.put("cabinId", snapshot.cabinId());
      value.put("cleaningTrigger", snapshot.cleaningTrigger());
      value.put("startsAt", snapshot.startsAt());
      value.put("expectedEndsAt", snapshot.expectedEndsAt());
      if (snapshot.completedAt() != null) value.put("completedAt", snapshot.completedAt());
      value.put("version", snapshot.version());
      output.add(value);
    }
    return output;
  }

  private static final class QueryRejected extends RuntimeException {
    private final String code;

    private QueryRejected(String code) {
      super(code);
      this.code = code;
    }
  }
}
