package com.estoma.washexecution.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CleaningPeriod;
import com.estoma.washexecution.domain.execution.CleaningTrigger;
import com.estoma.washexecution.domain.execution.EntryDecision;
import com.estoma.washexecution.domain.execution.EntryValidation;
import com.estoma.washexecution.domain.execution.ExitMaterials;
import com.estoma.washexecution.domain.execution.ResourceUnavailability;
import com.estoma.washexecution.domain.execution.UnavailabilityCauseType;
import com.estoma.washexecution.domain.execution.WashExecution;
import com.estoma.washexecution.domain.execution.WashExecutionStatus;
import com.estoma.washexecution.domain.shared.DomainRuleViolationException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WashExecutionDomainTest {
  private static final Instant NOW = Instant.parse("2026-08-22T18:00:00Z");

  @Test
  void authorizedEntryWithoutResourceBecomesPendingReassignment() {
    WashExecution execution = WashExecution.register(UUID.randomUUID(), UUID.randomUUID(), AppointmentType.NORMAL, NOW, NOW);
    execution.authorizeWithoutResource();
    assertEquals(WashExecutionStatus.PENDING_REASSIGNMENT, execution.status());
    execution.start(NOW.plusSeconds(1));
    assertEquals(WashExecutionStatus.IN_PROGRESS, execution.status());
  }

  @Test
  void directCancellationFromInProgressIsRejected() {
    WashExecution execution = WashExecution.register(UUID.randomUUID(), UUID.randomUUID(), AppointmentType.NORMAL, NOW, NOW);
    execution.start(NOW);
    assertThrows(DomainRuleViolationException.class,
        () -> execution.cancelByClinic(UUID.randomUUID(), "NO_RESOURCE", "No compatible resource", NOW));
  }

  @Test
  void completionRequiresSubmittedExitAndAllowsSupervisorCorrection() {
    WashExecution execution = WashExecution.register(UUID.randomUUID(), UUID.randomUUID(), AppointmentType.NORMAL, NOW, NOW);
    execution.start(NOW);
    assertThrows(DomainRuleViolationException.class,
        () -> execution.complete(UUID.randomUUID(), new ExitMaterials(1, 0, 0, 0), NOW));
    execution.submitExit(UUID.randomUUID(), new ExitMaterials(1, 0, 0, 0), NOW);
    execution.complete(UUID.randomUUID(), new ExitMaterials(0, 0, 2, 0), NOW.plusSeconds(1));
    assertEquals(WashExecutionStatus.COMPLETED, execution.status());
    assertEquals(2, execution.exitMaterials().greenPaperCassette10Count());
  }

  @Test
  void entryValidationEnforcesDecisionInvariants() {
    UUID executionId = UUID.randomUUID();
    assertThrows(DomainRuleViolationException.class, () -> new EntryValidation(UUID.randomUUID(), executionId,
        false, true, EntryDecision.AUTHORIZED, null, UUID.randomUUID(), NOW));
    assertThrows(DomainRuleViolationException.class, () -> new EntryValidation(UUID.randomUUID(), executionId,
        false, false, null, null, UUID.randomUUID(), NOW));
    new EntryValidation(UUID.randomUUID(), executionId, false, false, EntryDecision.REJECTED,
        "Identity mismatch", UUID.randomUUID(), NOW);
  }

  @Test
  void cleaningEndsAtThreeHoursAndRejectsManualCompletionState() {
    CleaningPeriod period = new CleaningPeriod(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        CleaningTrigger.NORMAL_COMPLETION, NOW, 1);
    assertEquals(NOW.plusSeconds(3 * 60 * 60), period.expectedEndsAt());
    assertThrows(DomainRuleViolationException.class, () -> period.completeAutomatically(NOW.plusSeconds(1)));
    period.completeAutomatically(NOW.plusSeconds(3 * 60 * 60));
    assertEquals(period.expectedEndsAt(), period.completedAt());

    assertThrows(DomainRuleViolationException.class, () -> CleaningPeriod.reconstitute(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        CleaningTrigger.NORMAL_COMPLETION, NOW, NOW.plusSeconds(3 * 60 * 60), UUID.randomUUID(),
        NOW.plusSeconds(3 * 60 * 60), 2));
    assertThrows(DomainRuleViolationException.class, () -> CleaningPeriod.reconstitute(
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        CleaningTrigger.NORMAL_COMPLETION, NOW, NOW.plusSeconds(2 * 60 * 60), null, null, 1));
  }

  @Test
  void resourceRestoreAllowsOptionalResolutionButPreservesTimeOrder() {
    ResourceUnavailability unavailable = new ResourceUnavailability(
        UUID.randomUUID(), null, UUID.randomUUID(), null, UnavailabilityCauseType.FAILURE,
        "Mechanical failure", UUID.randomUUID(), NOW, 1);
    unavailable.restore(UUID.randomUUID(), NOW.plusSeconds(1), null);
    assertNull(unavailable.resolution());

    ResourceUnavailability second = new ResourceUnavailability(
        UUID.randomUUID(), null, null, UUID.randomUUID(), UnavailabilityCauseType.MANUAL_DISABLE,
        "Maintenance", UUID.randomUUID(), NOW, 1);
    assertThrows(DomainRuleViolationException.class,
        () -> second.restore(UUID.randomUUID(), NOW.minusSeconds(1), "too early"));
  }

  @Test
  void arrivalAndLifecycleTimesCannotMoveBackwards() {
    assertThrows(DomainRuleViolationException.class, () -> WashExecution.register(
        UUID.randomUUID(), UUID.randomUUID(), AppointmentType.NORMAL, NOW.plusSeconds(1), NOW));

    WashExecution execution = WashExecution.register(UUID.randomUUID(), UUID.randomUUID(), AppointmentType.NORMAL, NOW, NOW);
    assertThrows(DomainRuleViolationException.class, () -> execution.start(NOW.minusSeconds(1)));
  }
}
