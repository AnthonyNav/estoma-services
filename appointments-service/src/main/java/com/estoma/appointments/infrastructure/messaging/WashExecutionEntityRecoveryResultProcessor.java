package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.out.MissedEvaluationRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.WashExecutionOperationalProjectionRepository;
import com.estoma.appointments.application.recovery.WashExecutionSnapshotReconciler;
import com.estoma.appointments.contracts.MessageEnvelope;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Applies one authoritative Execution entity repair without changing FULL projection readiness. */
@Service
public class WashExecutionEntityRecoveryResultProcessor {
  private final ProcessedMessageRepository inbox;
  private final WashExecutionOperationalProjectionRepository execution;
  private final WashExecutionSnapshotReconciler reconciler;
  private final MissedEvaluationRepository missedEvaluations;
  private final OutboxRepository outbox;
  private final Clock clock;

  public WashExecutionEntityRecoveryResultProcessor(ProcessedMessageRepository inbox,
      WashExecutionOperationalProjectionRepository execution,
      WashExecutionSnapshotReconciler reconciler, MissedEvaluationRepository missedEvaluations,
      OutboxRepository outbox, Clock clock) {
    this.inbox = inbox;
    this.execution = execution;
    this.reconciler = reconciler;
    this.missedEvaluations = missedEvaluations;
    this.outbox = outbox;
    this.clock = clock;
  }

  @Transactional
  public void process(MessageEnvelope result) {
    Instant now = clock.instant();
    if (!inbox.tryClaim(result.messageId(), result.messageType(), now)) return;
    if (result.causationId() == null) {
      throw new InvalidMessageException("ENTITY recovery Result requires causationId");
    }
    var missedEvaluation = missedEvaluations.findByQueryMessageId(result.causationId()).orElse(null);
    String status = text(result.payload(), "status");
    if (!"SUCCEEDED".equals(status)) {
      // Fail closed. A terminal Query failure never authorizes MISSED.
      inbox.markApplied(result.messageId(), now);
      return;
    }

    JsonNode data = object(result.payload(), "data");
    UUID appointmentId = uuid(data, "appointmentId");
    Instant generated = result.occurredAt();
    JsonNode snapshot = data.path("washExecution");
    Instant arrivedAt = snapshot.isObject() ? optionalInstant(snapshot, "arrivedAt") : null;
    if (snapshot.isObject()) reconcile(snapshot, appointmentId, generated);
    applyOperationalCollections(data, generated);

    if (missedEvaluation != null) {
      if (!missedEvaluation.appointmentId().equals(appointmentId)) {
        throw new InvalidMessageException("MISSED confirmation appointmentId mismatch");
      }
      boolean confirmed = missedEvaluations.confirm(appointmentId,
          missedEvaluation.expectedAppointmentVersion(), result.causationId(), result.messageId(),
          generated, arrivedAt, now);
      if (confirmed) {
        enqueueMissedRetry(result, missedEvaluation, now);
      }
    }
    inbox.markApplied(result.messageId(), now);
  }

  private void enqueueMissedRetry(MessageEnvelope result,
      MissedEvaluationRepository.Evaluation evaluation, Instant now) {
    UUID messageId = UUID.randomUUID();
    String payload = "{\"appointmentId\":\"" + evaluation.appointmentId()
        + "\",\"expectedVersion\":" + evaluation.expectedAppointmentVersion() + "}";
    outbox.append(new OutboxRepository.OutboxMessage(messageId, "MarkAppointmentAsMissed", 1,
        "COMMAND", evaluation.appointmentId(), evaluation.expectedAppointmentVersion(),
        result.correlationId(), result.messageId(), payload, now, now,
        "PENDING", 0, null, null));
  }

  private void reconcile(JsonNode x, UUID appointmentId, Instant generated) {
    long version = positiveLong(x, "version");
    reconciler.reconcile(new WashExecutionSnapshotReconciler.ExecutionSnapshot(
        uuid(x,"washExecutionId"), appointmentId, text(x,"status"), version,
        optionalInstant(x,"arrivedAt"), optionalInstant(x,"startedAt"), optionalInstant(x,"entryAuthorizedAt"),
        optionalInstant(x,"rejectedAt"), optionalText(x,"rejectionReason"), optionalUuid(x,"rejectedByAccountId"),
        optionalInstant(x,"completedAt"), optionalUuid(x,"completedByAccountId"), optionalInstant(x,"cancelledAt"),
        optionalText(x,"cancellationType"), optionalText(x,"cancellationSubreason"),
        optionalText(x,"cancellationReason"), optionalUuid(x,"cancelledByAccountId")), generated);
  }

  private void applyOperationalCollections(JsonNode data, Instant generated) {
    for (JsonNode x : array(data, "resourceUnavailabilities")) {
      execution.upsertResourceUnavailability(
          new WashExecutionOperationalProjectionRepository.ResourceUnavailabilityProjection(
              uuid(x,"resourceUnavailabilityId"), optionalUuid(x,"cabinId"), optionalUuid(x,"tankId"),
              instant(x,"disabledAt"), optionalInstant(x,"restoredAt"), positiveLong(x,"version"), generated));
    }
    for (JsonNode x : array(data, "cleaningPeriods")) {
      execution.upsertCleaningPeriod(new WashExecutionOperationalProjectionRepository.CleaningPeriodProjection(
          uuid(x,"cleaningPeriodId"), uuid(x,"washExecutionId"), uuid(x,"resourceAssignmentId"),
          uuid(x,"cabinId"), text(x,"cleaningTrigger"), instant(x,"startsAt"),
          instant(x,"expectedEndsAt"), optionalInstant(x,"completedAt"), positiveLong(x,"version"), generated));
    }
  }

  private static JsonNode object(JsonNode n, String field) {
    JsonNode value = n.path(field);
    if (!value.isObject()) throw new InvalidMessageException(field + " must be object");
    return value;
  }
  private static JsonNode array(JsonNode n, String field) {
    JsonNode value = n.path(field);
    if (!value.isArray()) throw new InvalidMessageException(field + " must be array");
    return value;
  }
  private static String text(JsonNode n, String field) {
    String value = optionalText(n, field);
    if (value == null || value.isBlank()) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static String optionalText(JsonNode n, String field) {
    return !n.has(field) || n.path(field).isNull() || !n.path(field).isTextual()
        ? null : n.path(field).asString();
  }
  private static UUID uuid(JsonNode n, String field) {
    UUID value = optionalUuid(n, field);
    if (value == null) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static UUID optionalUuid(JsonNode n, String field) {
    String value = optionalText(n, field);
    if (value == null) return null;
    try { return UUID.fromString(value); }
    catch (RuntimeException exception) { throw new InvalidMessageException(field + " must be UUID", exception); }
  }
  private static long positiveLong(JsonNode n, String field) {
    long value = n.path(field).asLong(-1);
    if (value <= 0) throw new InvalidMessageException(field + " must be positive");
    return value;
  }
  private static Instant instant(JsonNode n, String field) {
    Instant value = optionalInstant(n, field);
    if (value == null) throw new InvalidMessageException(field + " is required");
    return value;
  }
  private static Instant optionalInstant(JsonNode n, String field) {
    String value = optionalText(n, field);
    if (value == null) return null;
    try { return Instant.parse(value); }
    catch (RuntimeException exception) {
      throw new InvalidMessageException(field + " must be ISO-8601 instant", exception);
    }
  }
}
