package com.estoma.washexecution.application.query;

import com.estoma.washexecution.application.authorization.ProjectionReadinessGuard;
import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.command.CommandRejectedException;
import com.estoma.washexecution.application.port.JsonCodec;
import com.estoma.washexecution.application.port.OutboxRepository;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.application.port.WashExecutionIntegrationQueryRepository;
import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.application.resource.ResourceAllocator;
import com.estoma.washexecution.domain.execution.WashExecutionStatus;
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
  private final WashExecutionRepository executions;
  private final ProjectionRepositories.AppointmentRepository appointments;
  private final ProjectionRepositories.TimeSlotRepository slots;
  private final ProjectionReadinessGuard readiness;
  private final ResourceAllocator allocator;
  private final OutboxRepository outbox;
  private final JsonCodec json;
  private final Clock clock;

  public WashExecutionIntegrationQueryService(
      WashExecutionIntegrationQueryRepository repository,
      WashExecutionRepository executions,
      ProjectionRepositories.AppointmentRepository appointments,
      ProjectionRepositories.TimeSlotRepository slots,
      ProjectionReadinessGuard readiness,
      ResourceAllocator allocator,
      OutboxRepository outbox,
      JsonCodec json,
      Clock clock) {
    this.repository = repository;
    this.executions = executions;
    this.appointments = appointments;
    this.slots = slots;
    this.readiness = readiness;
    this.allocator = allocator;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
  }

  public void respond(IntegrationQueryMetadata metadata, WashExecutionIntegrationQuery query) {
    Map<String, Object> payload = new LinkedHashMap<>();
    try {
      requireRequester(metadata.producer(), query);
      payload.put("status", "SUCCEEDED");
      if (query instanceof WashExecutionIntegrationQuery.GetContext get) {
        if (get.appointmentId() == null) throw new QueryRejected("INVALID_APPOINTMENT_ID");
        Map<String, Object> data = entity(repository.get(get.appointmentId()));
        data.put("snapshotGeneratedAt", clock.instant());
        payload.put("data", data);
      } else if (query instanceof WashExecutionIntegrationQuery.ListPage page) {
        if (page.serviceDate() == null) throw new QueryRejected("INVALID_SERVICE_DATE");
        int size = page.pageSize() == null ? 100 : page.pageSize();
        if (size < 1 || size > 200) throw new QueryRejected("INVALID_PAGE_SIZE");
        validatePageToken(page.pageToken());
        payload.put("data", page(repository.list(page.serviceDate(), size, page.pageToken())));
      } else if (query instanceof WashExecutionIntegrationQuery.GetReassignmentCandidates get) {
        payload.put("data", reassignmentCandidates(get.washExecutionId()));
      } else {
        throw new QueryRejected("UNSUPPORTED_QUERY");
      }
    } catch (QueryRejected rejection) {
      payload.clear();
      payload.put("status", "REJECTED");
      payload.put("error", Map.of("code", rejection.code));
    } catch (CommandRejectedException rejection) {
      payload.clear();
      payload.put("status", "REJECTED");
      payload.put("error", Map.of("code", rejection.code()));
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
        json.write(resultActor(metadata)),
        json.write(payload)));
  }

  private static void requireRequester(String producer, WashExecutionIntegrationQuery query) {
    boolean candidates = query instanceof WashExecutionIntegrationQuery.GetReassignmentCandidates;
    boolean allowed = candidates ? "bff".equals(producer) : "appointments".equals(producer);
    if (!allowed) {
      throw new QueryRejected("QUERY_PRODUCER_NOT_ALLOWED");
    }
  }

  private static ActorContext resultActor(IntegrationQueryMetadata metadata) {
    return "GetReassignmentCandidates".equals(metadata.messageType())
        ? ActorContext.system(PRODUCER)
        : metadata.actor();
  }

  private Map<String, Object> reassignmentCandidates(UUID washExecutionId) {
    if (washExecutionId == null) throw new QueryRejected("INVALID_WASH_EXECUTION_ID");
    var execution = executions.findById(washExecutionId)
        .orElseThrow(() -> new QueryRejected("WASH_EXECUTION_NOT_FOUND"));
    if (execution.status() != WashExecutionStatus.PENDING_REASSIGNMENT) {
      throw new QueryRejected("REASSIGNMENT_NOT_PENDING");
    }
    readiness.requireReady(
        ProjectionReadinessGuard.APPOINTMENTS,
        "APPOINTMENT_CONTEXT_UNAVAILABLE");
    var appointment = appointments.findById(execution.appointmentId())
        .orElseThrow(() -> new QueryRejected("APPOINTMENT_CONTEXT_UNAVAILABLE"));
    if (!"SCHEDULED".equals(appointment.status()) && !"IN_PROGRESS".equals(appointment.status())) {
      throw new QueryRejected("APPOINTMENT_CONTEXT_UNAVAILABLE");
    }
    var slot = slots.findById(appointment.appointmentTimeSlotId())
        .orElseThrow(() -> new QueryRejected("APPOINTMENT_CONTEXT_UNAVAILABLE"));

    Instant generatedAt = clock.instant();
    List<ProjectionRepositories.ResourceCandidate> candidates = allocator.snapshotCandidates(
        execution.appointmentType(), slot, generatedAt);
    List<Map<String, Object>> values = candidates.stream()
        .map(WashExecutionIntegrationQueryService::candidate)
        .toList();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("washExecutionId", execution.washExecutionId());
    data.put("executionVersion", execution.version());
    data.put("snapshotGeneratedAt", generatedAt);
    data.put("freshUntil", generatedAt.plusSeconds(10));
    data.put("recommendedCandidate", values.isEmpty() ? null : values.getFirst());
    data.put("candidates", values);
    return data;
  }

  private static Map<String, Object> candidate(ProjectionRepositories.ResourceCandidate candidate) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("cabinId", candidate.cabinId());
    value.put("cabinCode", candidate.cabinCode());
    value.put("cabinName", candidate.cabinName());
    value.put("tankId", candidate.tankId());
    value.put("tankCode", candidate.tankCode());
    value.put("tankName", candidate.tankName());
    value.put("availableCapacity", candidate.remainingCapacity());
    return value;
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
    putIfPresent(value, "startedAt", execution.startedAt());
    putIfPresent(value, "entryAuthorizedAt", execution.entryAuthorizedAt());
    putIfPresent(value, "rejectedAt", execution.rejectedAt());
    putIfPresent(value, "rejectionReason", execution.rejectionReason());
    putIfPresent(value, "rejectedByAccountId", execution.rejectedByAccountId());
    putIfPresent(value, "completedAt", execution.completedAt());
    putIfPresent(value, "completedByAccountId", execution.completedByAccountId());
    putIfPresent(value, "cancelledAt", execution.cancelledAt());
    putIfPresent(value, "cancellationType", execution.cancellationType());
    putIfPresent(value, "cancellationSubreason", execution.cancellationSubreason());
    putIfPresent(value, "cancellationReason", execution.cancellationReason());
    putIfPresent(value, "cancelledByAccountId", execution.cancelledByAccountId());
    value.put("version", execution.version());
    return value;
  }

  private static void putIfPresent(Map<String, Object> target, String field, Object value) {
    if (value != null) target.put(field, value);
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
