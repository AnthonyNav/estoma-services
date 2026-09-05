package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.AvailableTimeSlot;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Context;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Snapshot;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Trust boundary for the asynchronous Appointments availability Result contract. */
@Service
public class AppointmentAvailabilityResultService {
  private static final String TYPE = "GetAppointmentAvailabilityResult";
  private static final String PRODUCER = "appointments";
  private static final String CONSUMER = "bff-appointment-availability-results";
  private static final Set<String> DATA_FIELDS = Set.of(
      "snapshotId", "snapshotGeneratedAt", "freshUntil", "requestContext", "canSchedule",
      "blockingReasons", "dailyPenaltyPoints", "dailyCompletedAppointments",
      "exceptionalAuthorizationRequired", "exceptionalAuthorizationAvailable",
      "exceptionalAuthorizationId", "availableTimeSlots");
  private static final Set<String> CONTEXT_FIELDS = Set.of(
      "studentAccountId", "serviceDate", "appointmentType", "instrumentCount", "pieceType",
      "courseSectionId");
  private static final Set<String> SLOT_FIELDS = Set.of(
      "appointmentTimeSlotId", "startsAt", "endsAt", "availableCapacity",
      "bookingDeadlineAt");
  private static final List<String> BLOCKING_REASON_ORDER = List.of(
      "ACTIVE_APPOINTMENT_EXISTS", "STUDENT_BLOCKED", "DAILY_COMPLETED_LIMIT",
      "EXCEPTIONAL_AUTHORIZATION_REQUIRED", "ACADEMIC_ENROLLMENT_INVALID",
      "STUDENT_ACCESS_DENIED");
  private static final Set<String> BLOCKING_REASONS = Set.copyOf(BLOCKING_REASON_ORDER);

  private final AppointmentAvailabilityPort availability;
  private final InboxRepository inbox;
  private final Clock clock;
  private static final Duration OWNER_FRESHNESS = Duration.ofSeconds(30);

  public AppointmentAvailabilityResultService(AppointmentAvailabilityPort availability,
      InboxRepository inbox, Clock clock) {
    this.availability = availability;
    this.inbox = inbox;
    this.clock = clock;
  }

  @Transactional
  public boolean apply(IncomingResult result) {
    if (!validEnvelope(result)) return false;
    var expected = availability.findRefreshByMessageId(result.causationId()).orElse(null);
    if (expected == null || !expected.correlationId().equals(result.correlationId())) return false;

    if ("REJECTED".equals(result.status())) {
      if (!exact(result.payload(), Set.of("status", "error"))
          || !"INVALID_AVAILABILITY_CONTEXT".equals(result.errorCode())
          || result.data() != null || result.errorDetailsJson() == null) return false;
      Map<String, Object> error;
      try {
        error = object(result.payload().get("error"), "error");
      } catch (RuntimeException invalid) {
        return false;
      }
      if (!exact(error, Set.of("code"))
          || !"INVALID_AVAILABILITY_CONTEXT".equals(error.get("code"))) return false;
      if (!recordIfNew(result)) return true;
      availability.rejectRefresh(expected, result.messageId(), result.errorCode(), clock.instant());
      return true;
    }
    if (!"SUCCEEDED".equals(result.status()) || result.errorCode() != null
        || result.errorDetailsJson() != null || !exact(result.payload(), Set.of("status", "data"))) {
      return false;
    }

    Snapshot snapshot;
    try {
      snapshot = snapshot(result, expected.context());
    } catch (RuntimeException invalid) {
      return false;
    }
    if (!recordIfNew(result)) return true;
    availability.applySnapshot(expected, result.messageId(), snapshot, clock.instant());
    return true;
  }

  private Snapshot snapshot(IncomingResult result, Context expectedContext) {
    Map<String, Object> data = object(result.data(), "data");
    requireExact(data, DATA_FIELDS, "data");
    UUID snapshotId = uuid(data, "snapshotId");
    if (!snapshotId.equals(result.messageId())) {
      throw new IllegalArgumentException("snapshotId must equal Result messageId");
    }
    Instant generatedAt = instant(data, "snapshotGeneratedAt");
    Instant freshUntil = instant(data, "freshUntil");
    if (generatedAt.isAfter(result.occurredAt()) || freshUntil.isBefore(generatedAt)) {
      throw new IllegalArgumentException("invalid snapshot freshness interval");
    }
    Context requestContext = context(object(data.get("requestContext"), "requestContext"));
    if (!requestContext.equals(expectedContext)) {
      throw new IllegalArgumentException("snapshot requestContext does not match the Query");
    }
    boolean canSchedule = bool(data, "canSchedule");
    List<String> blockers = strings(data, "blockingReasons");
    int penalty = nonnegativeInt(data, "dailyPenaltyPoints");
    int completed = nonnegativeInt(data, "dailyCompletedAppointments");
    boolean authorizationRequired = bool(data, "exceptionalAuthorizationRequired");
    boolean authorizationAvailable = bool(data, "exceptionalAuthorizationAvailable");
    UUID authorizationId = optionalUuid(data, "exceptionalAuthorizationId");
    List<AvailableTimeSlot> slots = slots(data, "availableTimeSlots");

    Instant expectedFreshUntil = generatedAt.plus(OWNER_FRESHNESS);
    for (AvailableTimeSlot slot : slots) {
      if (slot.bookingDeadlineAt().isBefore(expectedFreshUntil)) {
        expectedFreshUntil = slot.bookingDeadlineAt();
      }
    }
    if (!freshUntil.equals(expectedFreshUntil)) {
      throw new IllegalArgumentException("freshUntil does not match the owner contract");
    }
    return new Snapshot(requestContext, snapshotId, generatedAt, freshUntil, canSchedule, blockers,
        penalty, completed, authorizationRequired, authorizationAvailable, authorizationId, slots);
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

  private static Context context(Map<String, Object> value) {
    requireExact(value, CONTEXT_FIELDS, "requestContext");
    return new Context(uuid(value, "studentAccountId"),
        LocalDate.parse(text(value, "serviceDate")), text(value, "appointmentType"),
        integer(value, "instrumentCount"), text(value, "pieceType"),
        uuid(value, "courseSectionId"));
  }

  private static List<AvailableTimeSlot> slots(Map<String, Object> data, String field) {
    Object raw = data.get(field);
    if (!(raw instanceof List<?> values)) throw new IllegalArgumentException(field + " must be an array");
    List<AvailableTimeSlot> result = new ArrayList<>();
    Set<UUID> identifiers = new HashSet<>();
    for (Object item : values) {
      Map<String, Object> slot = object(item, "availableTimeSlot");
      requireExact(slot, SLOT_FIELDS, "availableTimeSlot");
      AvailableTimeSlot parsed = new AvailableTimeSlot(uuid(slot, "appointmentTimeSlotId"),
          instant(slot, "startsAt"), instant(slot, "endsAt"),
          integer(slot, "availableCapacity"), instant(slot, "bookingDeadlineAt"));
      if (!identifiers.add(parsed.appointmentTimeSlotId())) {
        throw new IllegalArgumentException("duplicate appointmentTimeSlotId");
      }
      result.add(parsed);
    }
    return List.copyOf(result);
  }

  private static List<String> strings(Map<String, Object> data, String field) {
    Object raw = data.get(field);
    if (!(raw instanceof List<?> values)) throw new IllegalArgumentException(field + " must be an array");
    List<String> result = new ArrayList<>();
    int priorOrder = -1;
    for (Object value : values) {
      if (!(value instanceof String text) || !BLOCKING_REASONS.contains(text)
          || result.contains(text)) {
        throw new IllegalArgumentException(field + " contains an unsupported or duplicate reason");
      }
      int order = BLOCKING_REASON_ORDER.indexOf(text);
      if (order <= priorOrder) {
        throw new IllegalArgumentException(field + " is not in canonical order");
      }
      priorOrder = order;
      result.add(text);
    }
    return List.copyOf(result);
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
    if (!(raw instanceof String text) || text.isBlank()) {
      throw new IllegalArgumentException(field + " must be text");
    }
    return text;
  }

  private static UUID uuid(Map<String, Object> value, String field) {
    return UUID.fromString(text(value, field));
  }

  private static UUID optionalUuid(Map<String, Object> value, String field) {
    Object raw = value.get(field);
    return raw == null ? null : UUID.fromString(text(value, field));
  }

  private static Instant instant(Map<String, Object> value, String field) {
    return Instant.parse(text(value, field));
  }

  private static int integer(Map<String, Object> value, String field) {
    Object raw = value.get(field);
    if (!(raw instanceof Number number)) throw new IllegalArgumentException(field + " must be an integer");
    long parsed = number.longValue();
    if (number.doubleValue() != parsed || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    return (int) parsed;
  }

  private static int nonnegativeInt(Map<String, Object> value, String field) {
    int parsed = integer(value, field);
    if (parsed < 0) throw new IllegalArgumentException(field + " cannot be negative");
    return parsed;
  }

  private static boolean bool(Map<String, Object> value, String field) {
    Object raw = value.get(field);
    if (!(raw instanceof Boolean result)) throw new IllegalArgumentException(field + " must be boolean");
    return result;
  }
}
