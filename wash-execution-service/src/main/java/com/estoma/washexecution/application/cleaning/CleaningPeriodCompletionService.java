package com.estoma.washexecution.application.cleaning;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.JsonCodec;
import com.estoma.washexecution.application.port.OutboxRepository;
import com.estoma.washexecution.application.port.WashExecutionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class CleaningPeriodCompletionService {
  private static final String PRODUCER = "wash-execution";

  private final CleaningPeriodRepository periods;
  private final WashExecutionRepository executions;
  private final OutboxRepository outbox;
  private final JsonCodec json;
  private final Clock clock;

  public CleaningPeriodCompletionService(
      CleaningPeriodRepository periods,
      WashExecutionRepository executions,
      OutboxRepository outbox,
      JsonCodec json,
      Clock clock) {
    this.periods = periods;
    this.executions = executions;
    this.outbox = outbox;
    this.json = json;
    this.clock = clock;
  }

  public int completeExpiredCleaningPeriods() {
    Instant now = clock.instant();
    int completed = 0;
    for (var period : periods.findExpiredForUpdate(now)) {
      int previousVersion = period.version();
      period.completeAutomatically(now);
      periods.save(period, previousVersion);
      var execution = executions.findById(period.washExecutionId())
          .orElseThrow(() -> new IllegalStateException("WASH_EXECUTION_NOT_FOUND_FOR_CLEANING"));

      Map<String, Object> payload = new LinkedHashMap<>();
      payload.put("cleaningPeriodId", period.cleaningPeriodId());
      payload.put("washExecutionId", period.washExecutionId());
      payload.put("appointmentId", execution.appointmentId());
      payload.put("resourceAssignmentId", period.resourceAssignmentId());
      payload.put("cabinId", period.cabinId());
      payload.put("completedByAccountId", null);
      payload.put("completedAt", period.completedAt());
      payload.put("cleaningPeriodVersion", period.version());

      outbox.append(new OutboxRepository.OutboxMessage(
          UUID.randomUUID(),
          "CleaningPeriodCompleted",
          1,
          "EVENT",
          PRODUCER,
          "CleaningPeriodCompleted",
          period.completedAt(),
          null,
          null,
          period.cleaningPeriodId(),
          period.version(),
          UUID.randomUUID(),
          null,
          json.write(ActorContext.system(PRODUCER)),
          json.write(payload)));
      completed++;
    }
    return completed;
  }
}
