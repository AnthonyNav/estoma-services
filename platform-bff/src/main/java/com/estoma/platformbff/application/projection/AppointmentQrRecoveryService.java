package com.estoma.platformbff.application.projection;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.AppointmentQrRecoveryProjectionPort;
import com.estoma.platformbff.application.port.AppointmentQrRecoveryProjectionPort.ApplyOutcome;
import com.estoma.platformbff.application.port.AppointmentQrRecoveryProjectionPort.Snapshot;
import com.estoma.platformbff.application.port.AppointmentQrRecoveryRepository;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.ProjectionRecoveryJobRepository;
import com.estoma.platformbff.application.port.ProjectionRecoveryQueryRepository;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import com.estoma.platformbff.domain.projection.AppointmentQrRecoveryState;
import com.estoma.platformbff.domain.projection.RecoveryJob;
import com.estoma.platformbff.domain.projection.RecoveryQuery;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable, date-scoped two-pass recovery for owner-issued Appointment QR references. */
@Service
public class AppointmentQrRecoveryService {
  public static final String QUERY_TYPE = "ListAppointmentQrProjectionSnapshotsPage";
  public static final String RESULT_TYPE = QUERY_TYPE + "Result";
  private static final String PROJECTION = "appointment-qr";
  private static final String OWNER = "appointments";
  private static final String CONSUMER = "bff-appointment-qr-recovery";
  private static final Set<String> ACTIVE = Set.of("SCHEDULED", "IN_PROGRESS");
  private static final Set<String> TERMINAL = Set.of(
      "CANCELLED", "MISSED", "ENTRY_REJECTED", "COMPLETED");
  private static final Set<String> REJECTION_CODES = Set.of(
      "QR_PROJECTION_DISABLED", "INVALID_SERVICE_DATE", "INVALID_PAGE_SIZE", "INVALID_PAGE_TOKEN");

  private final AppointmentQrRecoveryRepository recovery;
  private final ProjectionRecoveryJobRepository jobs;
  private final ProjectionRecoveryQueryRepository queries;
  private final AppointmentQrRecoveryProjectionPort projection;
  private final InboxRepository inbox;
  private final int pageSize;
  private final int maxPagesPerPass;
  private final Duration resultTimeout;

  public AppointmentQrRecoveryService(AppointmentQrRecoveryRepository recovery,
      ProjectionRecoveryJobRepository jobs, ProjectionRecoveryQueryRepository queries,
      AppointmentQrRecoveryProjectionPort projection, InboxRepository inbox,
      @Value("${platform.recovery.appointment-qr.page-size:100}") int pageSize,
      @Value("${platform.recovery.appointment-qr.max-pages-per-pass:10000}") int maxPagesPerPass,
      @Value("${platform.recovery.appointment-qr.result-timeout:2m}") Duration resultTimeout) {
    if (pageSize < 1 || pageSize > 100) throw new IllegalArgumentException("QR recovery page size must be 1..100");
    if (maxPagesPerPass < 1) throw new IllegalArgumentException("QR recovery max pages must be positive");
    if (resultTimeout.isNegative() || resultTimeout.isZero()) {
      throw new IllegalArgumentException("QR recovery result timeout must be positive");
    }
    this.recovery = recovery;
    this.jobs = jobs;
    this.queries = queries;
    this.projection = projection;
    this.inbox = inbox;
    this.pageSize = pageSize;
    this.maxPagesPerPass = maxPagesPerPass;
    this.resultTimeout = resultTimeout;
  }

  /** Starts an absent date or explicitly restarts a completed/degraded date with a new generation. */
  @Transactional
  public UUID start(LocalDate serviceDate) {
    if (serviceDate == null) throw new IllegalArgumentException("serviceDate is required");
    recovery.lock(serviceDate);
    AppointmentQrRecoveryState current = recovery.find(serviceDate).orElse(null);
    if (current != null && "RUNNING".equals(current.status())) return current.recoveryJobId();
    return startNew(serviceDate, current, current == null ? 0 : current.restartRevision(), Instant.now());
  }

  /** Scheduler entry point: starts a new date, but never auto-restarts READY/DEGRADED state. */
  @Transactional
  public void maintain(LocalDate serviceDate, Instant now) {
    maintain(serviceDate, now, 0);
  }

  /** Applies a one-shot operator revision only after the current generation is terminal. */
  @Transactional
  public void maintain(LocalDate serviceDate, Instant now, long requestedRestartRevision) {
    if (serviceDate == null || now == null) throw new IllegalArgumentException("date and time are required");
    if (requestedRestartRevision < 0) throw new IllegalArgumentException("restart revision must not be negative");
    recovery.lock(serviceDate);
    AppointmentQrRecoveryState state = recovery.find(serviceDate).orElse(null);
    if (state == null) {
      startNew(serviceDate, null, requestedRestartRevision, now);
      return;
    }
    if (!"RUNNING".equals(state.status())) {
      if (requestedRestartRevision > state.restartRevision()) {
        startNew(serviceDate, state, requestedRestartRevision, now);
      }
      return;
    }
    RecoveryQuery current = queries.findById(state.currentQueryMessageId()).orElse(null);
    boolean exhausted = current == null
        || ("FAILED".equals(current.status()) && current.nextAttemptAt() == null);
    boolean timedOut = current != null && "PUBLISHED".equals(current.status())
        && current.publishedAt() != null && !current.publishedAt().plus(resultTimeout).isAfter(now);
    if (exhausted || timedOut) replaceCurrentQuery(state, now);
  }

  public boolean isReady(LocalDate serviceDate) {
    return recovery.find(serviceDate).map(state -> "READY".equals(state.status())).orElse(false);
  }

  @Transactional
  public boolean applyResult(IncomingResult result) {
    validateEnvelope(result);
    AppointmentQrRecoveryState initial = recovery.findByJobId(result.operationId()).orElse(null);
    if (initial == null) return isKnownSupersededResult(result);
    recovery.lock(initial.serviceDate());
    AppointmentQrRecoveryState state = recovery.find(initial.serviceDate()).orElse(null);
    if (state == null || !state.recoveryJobId().equals(result.operationId())) {
      return isKnownSupersededResult(result);
    }
    if (!"RUNNING".equals(state.status())) return true;
    RecoveryQuery current = queries.findById(state.currentQueryMessageId()).orElse(null);
    if (current == null || !state.currentQueryMessageId().equals(result.causationId())
        || !current.messageId().equals(result.causationId())
        || !current.recoveryJobId().equals(state.recoveryJobId())
        || current.generation() != state.generation()
        || !state.recoveryJobId().equals(result.correlationId())) {
      return true;
    }
    ParsedResult parsed;
    try {
      parsed = parsePayload(result, queryPageSize(current));
    } catch (IllegalArgumentException invalid) {
      recordAndDegrade(result, state, "INVALID_RECOVERY_RESULT", Instant.now());
      return true;
    }
    if (parsed.serviceDate() != null && !state.serviceDate().equals(parsed.serviceDate())) {
      recordAndDegrade(result, state, "SERVICE_DATE_MISMATCH", Instant.now());
      return true;
    }
    if (parsed.nextPageToken() != null
        && recovery.hasPageToken(state.recoveryJobId(), state.passNumber(), parsed.nextPageToken())) {
      recordAndDegrade(result, state, "PAGE_TOKEN_CYCLE", Instant.now());
      return true;
    }
    if (parsed.nextPageToken() != null && state.pageNumber() >= maxPagesPerPass) {
      recordAndDegrade(result, state, "PAGE_LIMIT_EXCEEDED", Instant.now());
      return true;
    }

    Instant now = Instant.now();
    if (!recordInbox(result, now)) return true;
    if (parsed.rejectionCode() != null) {
      degrade(state, parsed.rejectionCode(), now);
      return true;
    }
    if (projection.applyPage(parsed.snapshots(), now) == ApplyOutcome.CONFLICT) {
      degrade(state, "SAME_VERSION_CONFLICT", now);
      return true;
    }
    advance(state, parsed.nextPageToken(), now);
    return true;
  }

  private boolean isKnownSupersededResult(IncomingResult result) {
    RecoveryJob job = jobs.findById(result.operationId()).orElse(null);
    RecoveryQuery query = queries.findById(result.causationId()).orElse(null);
    if (job == null || query == null || !PROJECTION.equals(job.projectionName())
        || !OWNER.equals(job.ownerLogicalComponentId()) || !QUERY_TYPE.equals(job.queryType())
        || !job.recoveryJobId().equals(job.operationId())
        || !query.recoveryJobId().equals(job.recoveryJobId())
        || query.generation() != job.generation()
        || !result.correlationId().equals(job.recoveryJobId())) {
      return false;
    }
    Object rawDate = query.payload().get("serviceDate");
    if (!(rawDate instanceof String value)) return false;
    try {
      AppointmentQrRecoveryState current = recovery.find(LocalDate.parse(value)).orElse(null);
      return current != null && current.generation() > job.generation();
    } catch (RuntimeException invalid) {
      return false;
    }
  }

  private UUID startNew(LocalDate serviceDate, AppointmentQrRecoveryState previous,
      long restartRevision, Instant now) {
    long generation = recovery.nextGeneration();
    UUID jobId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    RecoveryJob job = new RecoveryJob(jobId, PROJECTION, "FULL", null, generation, OWNER,
        QUERY_TYPE, jobId, null, "RUNNING", now, now, null, null, queryId);
    jobs.insert(job);
    AppointmentQrRecoveryState state = new AppointmentQrRecoveryState(serviceDate, generation,
        restartRevision, jobId,
        1, 1, null, queryId, "RUNNING", now, now, null, null);
    if (previous == null) {
      recovery.insert(state);
    } else {
      recovery.replace(previous.generation(), state);
    }
    queries.insert(query(queryId, state, null, now));
    return jobId;
  }

  private void advance(AppointmentQrRecoveryState state, String nextPageToken, Instant now) {
    if (nextPageToken != null) {
      if (!recovery.recordPageToken(state.recoveryJobId(), state.passNumber(), nextPageToken)) {
        degrade(state, "PAGE_TOKEN_CYCLE", now);
        return;
      }
      createNext(state, state.passNumber(), state.pageNumber() + 1, nextPageToken, now);
    } else if (state.passNumber() == 1) {
      createNext(state, 2, 1, null, now);
    } else {
      AppointmentQrRecoveryState ready = new AppointmentQrRecoveryState(state.serviceDate(),
          state.generation(), state.restartRevision(), state.recoveryJobId(), 2, state.pageNumber(), state.pageToken(), null,
          "READY", state.startedAt(), now, now, null);
      recovery.update(ready);
      updateJob(ready, "COMPLETED", null, now, now);
    }
  }

  private void createNext(AppointmentQrRecoveryState state, int pass, int page, String token,
      Instant now) {
    UUID queryId = UUID.randomUUID();
    AppointmentQrRecoveryState next = new AppointmentQrRecoveryState(state.serviceDate(),
        state.generation(), state.restartRevision(), state.recoveryJobId(), pass, page, token, queryId, "RUNNING",
        state.startedAt(), now, null, null);
    recovery.update(next);
    updateJob(next, "RUNNING", null, now, null);
    queries.insert(query(queryId, next, token, now));
  }

  private void replaceCurrentQuery(AppointmentQrRecoveryState state, Instant now) {
    createNext(state, state.passNumber(), state.pageNumber(), state.pageToken(), now);
  }

  private void recordAndDegrade(IncomingResult result, AppointmentQrRecoveryState state,
      String error, Instant now) {
    if (recordInbox(result, now)) degrade(state, error, now);
  }

  private boolean recordInbox(IncomingResult result, Instant now) {
    return inbox.recordIfNew(new InboxMessage(CONSUMER, result.messageId(), MessageKind.RESULT.name(),
        result.messageType(), result.messageVersion(), result.producer(), result.correlationId(),
        result.causationId(), result.operationId(), result.aggregateId(), result.aggregateVersion(),
        now, now));
  }

  private void degrade(AppointmentQrRecoveryState state, String error, Instant now) {
    AppointmentQrRecoveryState degraded = new AppointmentQrRecoveryState(state.serviceDate(),
        state.generation(), state.restartRevision(), state.recoveryJobId(), state.passNumber(), state.pageNumber(),
        state.pageToken(), null, "DEGRADED", state.startedAt(), now, null, error);
    recovery.update(degraded);
    updateJob(degraded, "FAILED", error, now, now);
  }

  private void updateJob(AppointmentQrRecoveryState state, String status, String error,
      Instant now, Instant completedAt) {
    jobs.update(new RecoveryJob(state.recoveryJobId(), PROJECTION, "FULL", null, state.generation(),
        OWNER, QUERY_TYPE, state.recoveryJobId(), state.pageToken(), status, state.startedAt(), now,
        completedAt, error, state.currentQueryMessageId()));
  }

  private RecoveryQuery query(UUID queryId, AppointmentQrRecoveryState state, String token,
      Instant now) {
    Map<String, Object> payload = new java.util.LinkedHashMap<>();
    payload.put("serviceDate", state.serviceDate().toString());
    payload.put("pageSize", pageSize);
    if (token != null) payload.put("pageToken", token);
    return new RecoveryQuery(queryId, state.recoveryJobId(), state.generation(), QUERY_TYPE, 1,
        "platform.queries", OWNER, state.recoveryJobId(), null, queryId.toString(), null, payload,
        "PENDING", null, null, 0, null, null, null, now, now);
  }

  private static void validateEnvelope(IncomingResult result) {
    if (result == null || result.messageId() == null || result.messageKind() != MessageKind.RESULT
        || result.messageVersion() != 1 || !RESULT_TYPE.equals(result.messageType())
        || !OWNER.equals(result.producer()) || result.occurredAt() == null
        || result.correlationId() == null || result.causationId() == null
        || result.operationId() == null || result.idempotencyKey() != null
        || result.aggregateId() != null || result.aggregateVersion() != null
        || result.actor() == null || !"SYSTEM".equals(result.actor().type())
        || !OWNER.equals(result.actor().service()) || result.actor().accountId() != null
        || result.payload() == null) {
      throw new IllegalArgumentException("invalid QR recovery Result envelope");
    }
  }

  private static int queryPageSize(RecoveryQuery query) {
    Object value = query.payload().get("pageSize");
    long parsed = positiveLong(value, "query.pageSize");
    if (parsed > 100) throw new IllegalArgumentException("query.pageSize is invalid");
    return (int) parsed;
  }

  private static ParsedResult parsePayload(IncomingResult result, int expectedPageSize) {
    Map<String, Object> payload = result.payload();
    if (!java.util.Objects.equals(result.status(), payload.get("status"))) {
      throw new IllegalArgumentException("Result status does not match payload");
    }
    if ("REJECTED".equals(result.status())) {
      requireKeys(payload, Set.of("status", "error"));
      Map<String, Object> error = object(payload.get("error"), "error");
      requireKeys(error, Set.of("code"));
      String code = text(error.get("code"), "error.code", 160);
      if (!REJECTION_CODES.contains(code)) throw new IllegalArgumentException("invalid rejection code");
      return new ParsedResult(null, List.of(), null, code);
    }
    if (!"SUCCEEDED".equals(result.status())) throw new IllegalArgumentException("invalid Result status");
    requireKeys(payload, Set.of("status", "data"));
    Map<String, Object> data = object(payload.get("data"), "data");
    Set<String> keys = Set.of("snapshotGeneratedAt", "serviceDate", "items", "nextPageToken");
    if (!keys.containsAll(data.keySet())
        || !data.keySet().containsAll(Set.of("snapshotGeneratedAt", "serviceDate", "items"))) {
      throw new IllegalArgumentException("invalid QR recovery data shape");
    }
    instant(data.get("snapshotGeneratedAt"), "snapshotGeneratedAt");
    LocalDate serviceDate;
    try {
      serviceDate = LocalDate.parse(text(data.get("serviceDate"), "serviceDate", 10));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("invalid serviceDate", invalid);
    }
    if (!(data.get("items") instanceof List<?> rawItems) || rawItems.size() > expectedPageSize) {
      throw new IllegalArgumentException("invalid items");
    }
    List<Snapshot> snapshots = new ArrayList<>();
    Set<UUID> ids = new HashSet<>();
    for (Object raw : rawItems) {
      Map<String, Object> item = object(raw, "item");
      requireKeys(item, Set.of("appointmentId", "appointmentVersion", "appointmentStatus",
          "entryAuthorizedAt", "qrRepresentation"));
      UUID id = uuid(item.get("appointmentId"), "appointmentId");
      if (!ids.add(id)) throw new IllegalArgumentException("duplicate appointmentId in page");
      long version = positiveLong(item.get("appointmentVersion"), "appointmentVersion");
      String status = text(item.get("appointmentStatus"), "appointmentStatus", 32);
      if (!ACTIVE.contains(status) && !TERMINAL.contains(status)) {
        throw new IllegalArgumentException("invalid appointmentStatus");
      }
      if (item.get("entryAuthorizedAt") != null) {
        instant(item.get("entryAuthorizedAt"), "entryAuthorizedAt");
      }
      String qr = item.get("qrRepresentation") == null ? null
          : text(item.get("qrRepresentation"), "qrRepresentation", 2000);
      if ((ACTIVE.contains(status) && qr == null) || (TERMINAL.contains(status) && qr != null)) {
        throw new IllegalArgumentException("QR representation does not match appointment status");
      }
      snapshots.add(new Snapshot(id, version, qr));
    }
    String next = null;
    if (data.containsKey("nextPageToken")) {
      next = text(data.get("nextPageToken"), "nextPageToken", 160);
    }
    return new ParsedResult(serviceDate, List.copyOf(snapshots), next, null);
  }

  private static void requireKeys(Map<String, Object> value, Set<String> keys) {
    if (!value.keySet().equals(keys)) throw new IllegalArgumentException("invalid object shape");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String name) {
    if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " must be an object");
    return (Map<String, Object>) map;
  }

  private static String text(Object value, String name, int maxCodePoints) {
    if (!(value instanceof String text) || text.isEmpty()
        || text.codePointCount(0, text.length()) > maxCodePoints) {
      throw new IllegalArgumentException(name + " is invalid");
    }
    return text;
  }

  private static UUID uuid(Object value, String name) {
    try {
      return UUID.fromString(text(value, name, 36));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(name + " is invalid", invalid);
    }
  }

  private static Instant instant(Object value, String name) {
    try {
      return Instant.parse(text(value, name, 64));
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException(name + " is invalid", invalid);
    }
  }

  private static long positiveLong(Object value, String name) {
    if (!(value instanceof Byte || value instanceof Short || value instanceof Integer
        || value instanceof Long)) throw new IllegalArgumentException(name + " must be an integer");
    long result = ((Number) value).longValue();
    if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
    return result;
  }

  private record ParsedResult(LocalDate serviceDate, List<Snapshot> snapshots,
                              String nextPageToken, String rejectionCode) {}
}
