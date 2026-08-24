package com.estoma.washexecution.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.CompleteExpiredCleaningPeriodsUseCase;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.AssignmentType;
import com.estoma.washexecution.domain.execution.CleaningPeriod;
import com.estoma.washexecution.domain.execution.CleaningTrigger;
import com.estoma.washexecution.domain.execution.ReleaseReason;
import com.estoma.washexecution.domain.execution.ResourceAssignment;
import com.estoma.washexecution.domain.execution.WashExecution;
import com.estoma.washexecution.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class CleaningPeriodCompletionIT extends AbstractPostgresIT {
  private static final Instant START = Instant.parse("2026-08-23T08:00:00Z");

  @Autowired CompleteExpiredCleaningPeriodsUseCase useCase;
  @Autowired WashExecutionRepository executions;
  @Autowired ResourceAssignmentRepository assignments;
  @Autowired CleaningPeriodRepository cleanings;

  @Test
  void completionUsesExpectedEndAndIsIdempotent() {
    UUID appointmentId = UUID.randomUUID();
    UUID executionId = UUID.randomUUID();
    WashExecution execution = WashExecution.register(
        executionId, appointmentId, AppointmentType.IMMUNOCOMPROMISED, START, START.plusSeconds(1));
    executions.save(execution, 0);
    execution.start(START.plusSeconds(60));
    executions.save(execution, 1);

    UUID assignmentId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    ResourceAssignment assignment = new ResourceAssignment(
        assignmentId, executionId, UUID.randomUUID(), cabinId, UUID.randomUUID(),
        AssignmentType.INITIAL, null, START.plusSeconds(60));
    assignment.release(UUID.randomUUID(), START.plusSeconds(30 * 60L), ReleaseReason.NORMAL_COMPLETION);
    assignments.save(assignment);

    CleaningPeriod period = new CleaningPeriod(
        UUID.randomUUID(), executionId, assignmentId, cabinId,
        CleaningTrigger.NORMAL_COMPLETION, assignment.releasedAt(), 1);
    cleanings.save(period, 0);

    assertThat(useCase.completeExpiredCleaningPeriods()).isEqualTo(1);
    assertThat(useCase.completeExpiredCleaningPeriods()).isZero();

    CleaningPeriod completed = cleanings.findByAssignmentId(assignmentId).orElseThrow();
    assertThat(completed.completedAt()).isEqualTo(period.expectedEndsAt());
    assertThat(completed.completedByAccountId()).isNull();
    assertThat(completed.version()).isEqualTo(2);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM outbox_messages WHERE message_type='CleaningPeriodCompleted'",
        Integer.class)).isEqualTo(1);
    String payload = jdbc.queryForObject(
        "SELECT payload_json::text FROM outbox_messages WHERE message_type='CleaningPeriodCompleted'",
        String.class);
    assertThat(payload)
        .contains(appointmentId.toString())
        .contains(period.expectedEndsAt().toString());
  }
}
