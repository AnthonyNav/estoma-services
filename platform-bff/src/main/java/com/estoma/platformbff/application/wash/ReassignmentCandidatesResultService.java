package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Candidate;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Snapshot;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trust boundary for GetReassignmentCandidatesResult v1. */
@Service
public class ReassignmentCandidatesResultService {
  private static final String TYPE = "GetReassignmentCandidatesResult";
  private static final String PRODUCER = "wash-execution";
  private static final String CONSUMER = "bff-reassignment-candidates-results";
  private static final Duration OWNER_TTL = Duration.ofSeconds(10);
  private static final Set<String> EMPTY_DATA_FIELDS = Set.of("washExecutionId", "executionVersion",
      "snapshotGeneratedAt", "freshUntil", "candidates");
  private static final Set<String> NONEMPTY_DATA_FIELDS = Set.of("washExecutionId", "executionVersion",
      "snapshotGeneratedAt", "freshUntil", "recommendedCandidate", "candidates");
  private static final Set<String> CANDIDATE_FIELDS = Set.of("cabinId", "cabinCode", "cabinName",
      "tankId", "tankCode", "tankName", "availableCapacity");
  private static final Set<String> REJECTION_CODES = Set.of("WASH_EXECUTION_NOT_FOUND",
      "REASSIGNMENT_NOT_PENDING", "APPOINTMENT_CONTEXT_UNAVAILABLE",
      "RESOURCE_CONFIGURATION_UNAVAILABLE", "INVALID_WASH_EXECUTION_ID",
      "INVALID_QUERY_PAYLOAD", "QUERY_PRODUCER_NOT_ALLOWED", "UNSUPPORTED_QUERY");

  private final ReassignmentCandidatesPort candidates;
  private final InboxRepository inbox;
  private final Clock clock;

  public ReassignmentCandidatesResultService(ReassignmentCandidatesPort candidates,
      InboxRepository inbox, Clock clock) {
    this.candidates = candidates;
    this.inbox = inbox;
    this.clock = clock;
  }

  @Transactional
  public boolean apply(IncomingResult result) {
    if (!validEnvelope(result)) return false;
    var expected = candidates.findRefreshByMessageId(result.causationId()).orElse(null);
    if (expected == null || !expected.correlationId().equals(result.correlationId())) return false;

    if ("REJECTED".equals(result.status())) {
      if (!exact(result.payload(), Set.of("status", "error")) || result.data() != null
          || result.errorDetailsJson() == null || !REJECTION_CODES.contains(result.errorCode())) {
        return false;
      }
      Map<String, Object> error;
      try {
        error = object(result.payload().get("error"), "error");
      } catch (RuntimeException invalid) {
        return false;
      }
      if (!exact(error, Set.of("code")) || !result.errorCode().equals(error.get("code"))) return false;
      if (!recordIfNew(result)) return true;
      candidates.rejectRefresh(expected, result.messageId(), result.errorCode(), clock.instant());
      return true;
    }
    if (!"SUCCEEDED".equals(result.status()) || result.errorCode() != null
        || result.errorDetailsJson() != null || !exact(result.payload(), Set.of("status", "data"))) {
      return false;
    }

    Snapshot snapshot;
    try {
      snapshot = snapshot(result);
    } catch (RuntimeException invalid) {
      return false;
    }
    if (!snapshot.washExecutionId().equals(expected.washExecutionId())) return false;
    if (!recordIfNew(result)) return true;
    // A trusted owner can observe a newer execution version than the lagging BFF projection used
    // to schedule the Query. It is a benign late answer, but must never populate the old snapshot.
    if (snapshot.executionVersion() != expected.executionVersion()) return true;
    candidates.applySnapshot(expected, result.messageId(), snapshot, clock.instant());
    return true;
  }

  private static Snapshot snapshot(IncomingResult result) {
    Map<String, Object> data = object(result.data(), "data");
    if (!data.keySet().equals(EMPTY_DATA_FIELDS) && !data.keySet().equals(NONEMPTY_DATA_FIELDS)) {
      throw new IllegalArgumentException("data fields do not match V1");
    }
    UUID executionId = uuid(data, "washExecutionId");
    long executionVersion = positiveLong(data, "executionVersion");
    Instant generatedAt = instant(data, "snapshotGeneratedAt");
    Instant freshUntil = instant(data, "freshUntil");
    if (generatedAt.isAfter(result.occurredAt())
        || !freshUntil.equals(generatedAt.plus(OWNER_TTL))) {
      throw new IllegalArgumentException("invalid owner freshness interval");
    }
    List<Candidate> values = candidateList(data.get("candidates"));
    if (values.isEmpty()) {
      if (!data.keySet().equals(EMPTY_DATA_FIELDS)) {
        throw new IllegalArgumentException("empty snapshot must omit recommendation");
      }
    } else if (!data.keySet().equals(NONEMPTY_DATA_FIELDS)
        || !values.getFirst().equals(candidate(object(data.get("recommendedCandidate"),
            "recommendedCandidate")))) {
      throw new IllegalArgumentException("recommendation is not the first owner-ranked candidate");
    }
    return new Snapshot(executionId, executionVersion, result.messageId(), generatedAt, freshUntil,
        values);
  }

  private static List<Candidate> candidateList(Object raw) {
    if (!(raw instanceof List<?> items)) throw new IllegalArgumentException("candidates must be an array");
    List<Candidate> values = new ArrayList<>();
    Set<String> pairs = new HashSet<>();
    for (Object item : items) {
      Candidate value = candidate(object(item, "candidate"));
      if (!pairs.add(value.cabinId() + "|" + value.tankId())) {
        throw new IllegalArgumentException("duplicate resource candidate");
      }
      values.add(value);
    }
    return List.copyOf(values);
  }

  private static Candidate candidate(Map<String, Object> value) {
    requireExact(value, CANDIDATE_FIELDS, "candidate");
    return new Candidate(uuid(value, "cabinId"), text(value, "cabinCode"),
        text(value, "cabinName"), uuid(value, "tankId"), text(value, "tankCode"),
        text(value, "tankName"), positiveInt(value, "availableCapacity"));
  }

  private boolean recordIfNew(IncomingResult result) {
    Instant now = clock.instant();
    return inbox.recordIfNew(new InboxMessage(CONSUMER, result.messageId(), "RESULT",
        result.messageType(), result.messageVersion(), result.producer(), result.correlationId(),
        result.causationId(), null, null, null, now, now));
  }

  private static boolean validEnvelope(IncomingResult result) {
    return result != null && result.messageId() != null && TYPE.equals(result.messageType())
        && result.messageVersion() == 1 && result.messageKind() == MessageKind.RESULT
        && PRODUCER.equals(result.producer()) && result.occurredAt() != null
        && result.correlationId() != null && result.causationId() != null
        && result.operationId() == null && result.idempotencyKey() == null
        && result.aggregateId() == null && result.aggregateVersion() == null
        && validActor(result.actor()) && result.status() != null;
  }

  private static boolean validActor(ActorContext actor) {
    return actor != null && "SYSTEM".equals(actor.type()) && actor.accountId() == null
        && PRODUCER.equals(actor.service()) && actor.scope() == null && actor.systemId() == null;
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> object(Object value, String field) {
    if (!(value instanceof Map<?, ?> raw)) throw new IllegalArgumentException(field + " must be an object");
    return (Map<String, Object>) raw;
  }

  private static boolean exact(Map<String, Object> value, Set<String> fields) {
    return value != null && value.keySet().equals(fields);
  }

  private static void requireExact(Map<String, Object> value, Set<String> fields, String name) {
    if (!exact(value, fields)) throw new IllegalArgumentException(name + " fields do not match V1");
  }

  private static String text(Map<String, Object> value, String field) {
    Object raw = value.get(field);
    if (!(raw instanceof String text) || text.isBlank()) throw new IllegalArgumentException(field);
    return text;
  }

  private static UUID uuid(Map<String, Object> value, String field) {
    return UUID.fromString(text(value, field));
  }

  private static Instant instant(Map<String, Object> value, String field) {
    return Instant.parse(text(value, field));
  }

  private static long nonnegativeLong(Map<String, Object> value, String field) {
    Object raw = value.get(field);
    if (!(raw instanceof Number number)) throw new IllegalArgumentException(field);
    long parsed = number.longValue();
    if (number.doubleValue() != parsed || parsed < 0) throw new IllegalArgumentException(field);
    return parsed;
  }

  private static int positiveInt(Map<String, Object> value, String field) {
    long parsed = positiveLong(value, field);
    if (parsed > Integer.MAX_VALUE) throw new IllegalArgumentException(field);
    return (int) parsed;
  }

  private static long positiveLong(Map<String, Object> value, String field) {
    long parsed = nonnegativeLong(value, field);
    if (parsed < 1) throw new IllegalArgumentException(field);
    return parsed;
  }
}
