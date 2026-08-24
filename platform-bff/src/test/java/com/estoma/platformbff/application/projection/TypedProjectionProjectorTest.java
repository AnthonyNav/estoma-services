package com.estoma.platformbff.application.projection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import com.estoma.platformbff.application.port.ProjectionReadModelPort;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
}
