package com.estoma.platformbff.application.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.ProjectionReadModelPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class TypedProjectionProjectorTest {

  @Test
  void washResourceReassignedReleasesPreviousAssignmentAndKeepsNewAssignmentActive() {
    List<ProjectionReadModel> projected = new ArrayList<>();
    ProjectionReadModelPort readModels = projected::add;
    TypedProjectionProjector projector = new TypedProjectionProjector(readModels, null);

    UUID executionId = UUID.randomUUID();
    UUID appointmentId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID previousAssignmentId = UUID.randomUUID();
    UUID previousCabinId = UUID.randomUUID();
    UUID previousTankId = UUID.randomUUID();
    UUID newAssignmentId = UUID.randomUUID();
    UUID newCabinId = UUID.randomUUID();
    UUID newTankId = UUID.randomUUID();
    UUID messageId = UUID.randomUUID();
    Instant releasedAt = Instant.parse("2026-08-24T14:00:00Z");
    Instant assignedAt = Instant.parse("2026-08-24T14:00:01Z");

    Map<String, Object> payload = new HashMap<>();
    payload.put("washExecutionId", executionId);
    payload.put("appointmentId", appointmentId);
    payload.put("appointmentTimeSlotId", slotId);
    payload.put("previousResourceAssignmentId", previousAssignmentId);
    payload.put("previousCabinId", previousCabinId);
    payload.put("previousTankId", previousTankId);
    payload.put("releasedAt", releasedAt);
    payload.put("newResourceAssignmentId", newAssignmentId);
    payload.put("newCabinId", newCabinId);
    payload.put("newTankId", newTankId);
    payload.put("assignmentType", "REASSIGNMENT");
    payload.put("assignedAt", assignedAt);

    projector.project(new ProjectionEvent(
        messageId,
        "WashResourceReassigned",
        1,
        "wash-execution",
        assignedAt,
        UUID.randomUUID(),
        UUID.randomUUID(),
        executionId,
        7,
        payload));

    assertEquals(2, projected.size());

    ProjectionReadModel.ResourceAssignment previous =
        (ProjectionReadModel.ResourceAssignment) projected.get(0);
    assertEquals(previousAssignmentId, previous.resourceAssignmentId());
    assertEquals(previousCabinId, previous.cabinId());
    assertEquals(previousTankId, previous.tankId());
    assertEquals(releasedAt, previous.releasedAt());
    assertEquals(7, previous.sourceVersion());

    ProjectionReadModel.ResourceAssignment replacement =
        (ProjectionReadModel.ResourceAssignment) projected.get(1);
    assertEquals(newAssignmentId, replacement.resourceAssignmentId());
    assertEquals(newCabinId, replacement.cabinId());
    assertEquals(newTankId, replacement.tankId());
    assertEquals("REASSIGNMENT", replacement.assignmentType());
    assertEquals(assignedAt, replacement.assignedAt());
    assertNull(replacement.releasedAt());
    assertEquals(7, replacement.sourceVersion());
  }

  @Test
  void malformedWashResourceReassignedIsRejectedBeforeInboxOrProjection() {
    AtomicBoolean inboxCalled = new AtomicBoolean(false);
    InboxRepository inbox = message -> {
      inboxCalled.set(true);
      return true;
    };
    ProjectionReadModelPort readModels = model -> {
      throw new AssertionError("malformed event must not reach the projector");
    };
    ProjectionEventRuntime runtime = new ProjectionEventRuntime(
        inbox, new TypedProjectionProjector(readModels, null));

    UUID executionId = UUID.randomUUID();
    Map<String, Object> payload = new HashMap<>();
    payload.put("washExecutionId", executionId);
    payload.put("previousCabinId", UUID.randomUUID());
    payload.put("previousTankId", UUID.randomUUID());
    payload.put("releasedAt", Instant.parse("2026-08-24T14:00:00Z"));
    payload.put("newResourceAssignmentId", UUID.randomUUID());
    payload.put("newCabinId", UUID.randomUUID());
    payload.put("newTankId", UUID.randomUUID());
    payload.put("assignmentType", "REASSIGNMENT");
    payload.put("assignedAt", Instant.parse("2026-08-24T14:00:01Z"));

    ProjectionEventRuntime.DispatchOutcome outcome = runtime.dispatch(new ProjectionEvent(
        UUID.randomUUID(),
        "WashResourceReassigned",
        1,
        "wash-execution",
        Instant.parse("2026-08-24T14:00:01Z"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        executionId,
        7,
        payload));

    assertEquals(ProjectionEventRuntime.DispatchOutcome.UNTRUSTED, outcome);
    assertFalse(inboxCalled.get());
  }

  @Test
  void washEntryRejectedProjectsTheOwnerSuppliedRejectionReason() {
    List<ProjectionReadModel> projected = new ArrayList<>();
    TypedProjectionProjector projector = new TypedProjectionProjector(projected::add, null);
    UUID executionId = UUID.randomUUID();
    String rejectionReason = "El material no coincide con la cita";

    projector.project(new ProjectionEvent(
        UUID.randomUUID(), "WashEntryRejected", 1, "wash-execution",
        Instant.parse("2026-08-27T12:00:00Z"), UUID.randomUUID(), UUID.randomUUID(), executionId, 4,
        Map.of("washExecutionId", executionId, "executionStatus", "ENTRY_REJECTED",
            "rejectionReason", rejectionReason)));

    ProjectionReadModel.WashExecution execution = (ProjectionReadModel.WashExecution) projected.getFirst();
    assertEquals("ENTRY_REJECTED", execution.status());
    assertEquals(rejectionReason, execution.rejectionReason());
  }

  @Test
  void appointmentScheduledProjectsExceptionalAuthorizationAndOwnerVersion() {
    List<ProjectionReadModel> projected = new ArrayList<>();
    TypedProjectionProjector projector = new TypedProjectionProjector(projected::add, null);
    UUID appointmentId = UUID.randomUUID();
    UUID exceptionalAuthorizationId = UUID.randomUUID();

    projector.project(new ProjectionEvent(
        UUID.randomUUID(), "AppointmentScheduled", 1, "appointments",
        Instant.parse("2026-08-27T12:00:00Z"), UUID.randomUUID(), UUID.randomUUID(), appointmentId, 7,
        Map.of("appointmentId", appointmentId, "appointmentTimeSlotId", UUID.randomUUID(),
            "studentAccountId", UUID.randomUUID(), "appointmentType", "NORMAL",
            "exceptionalAuthorizationId", exceptionalAuthorizationId)));

    ProjectionReadModel.Appointment appointment = (ProjectionReadModel.Appointment) projected.getFirst();
    assertEquals(exceptionalAuthorizationId, appointment.exceptionalAuthorizationId());
    assertEquals(Boolean.TRUE, appointment.exceptionalAuthorizationContextKnown());
    assertEquals(7, appointment.sourceVersion());
    assertEquals("SCHEDULED", appointment.status());
  }
}
