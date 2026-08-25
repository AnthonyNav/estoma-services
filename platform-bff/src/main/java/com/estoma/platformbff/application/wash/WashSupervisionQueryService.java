package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.port.WashSupervisionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.ActiveAssignmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.AppointmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.AppointmentTimeSlotResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.CourseSectionResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.PendingReassignmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.ResourceResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.StudentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.SubmittedExitMaterialsResponse;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.stereotype.Service;

@Service
public class WashSupervisionQueryService {
  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final WashSupervisionQueryPort projections;

  public WashSupervisionQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, WashSupervisionQueryPort projections) {
    this.authorization = authorization;
    this.readiness = readiness;
    this.projections = projections;
  }

  public PendingReassignmentsResponse pendingReassignments() {
    authorization.requireWashSupervisor();
    readiness.requireReady("wash-execution", "appointments", "academic", "person",
        "wash-administration");
    List<PendingReassignmentResponse> items = projections.findPendingReassignments().stream()
        .map(this::compose)
        .toList();
    return new PendingReassignmentsResponse(items);
  }

  private PendingReassignmentResponse compose(WashSupervisionQueryPort.PendingReassignment value) {
    CourseSectionResponse course = value.courseSectionId() == null ? null
        : new CourseSectionResponse(value.courseSectionId(), value.courseNrc(), value.courseName());
    AppointmentTimeSlotResponse slot = value.appointmentTimeSlotId() == null ? null
        : new AppointmentTimeSlotResponse(value.appointmentTimeSlotId(), value.serviceDate(),
            value.slotStartsAt(), value.slotEndsAt(), value.slotTimezone());
    AppointmentResponse appointment = value.appointmentId() == null ? null
        : new AppointmentResponse(value.appointmentId(), value.appointmentType(),
            value.instrumentCount(), value.pieceType(), value.appointmentStatus(), course, slot);
    StudentResponse student = value.studentAccountId() == null ? null
        : new StudentResponse(value.studentAccountId(), value.personId(), displayName(value),
            value.studentNumber(), value.currentSemester(), value.academicStatus());
    ActiveAssignmentResponse assignment = value.resourceAssignmentId() == null ? null
        : new ActiveAssignmentResponse(value.resourceAssignmentId(), value.assignmentType(),
            value.assignedAt(), resource(value.cabinId(), value.cabinCode(), value.cabinName()),
            resource(value.tankId(), value.tankCode(), value.tankName()));
    SubmittedExitMaterialsResponse submitted = submittedMaterials(value);
    return new PendingReassignmentResponse(value.washExecutionId(), value.executionStatus(),
        value.authorizedAt(), value.executionVersion(), appointment, student, assignment, submitted,
        "NONE");
  }

  private static SubmittedExitMaterialsResponse submittedMaterials(
      WashSupervisionQueryPort.PendingReassignment value) {
    if (value.submittedPackageCount() == null
        && value.submittedGreenPaperCassette8Count() == null
        && value.submittedGreenPaperCassette10Count() == null
        && value.submittedWitnessTapePortionCount() == null) {
      return null;
    }
    return new SubmittedExitMaterialsResponse(value.submittedPackageCount(),
        value.submittedGreenPaperCassette8Count(), value.submittedGreenPaperCassette10Count(),
        value.submittedWitnessTapePortionCount());
  }

  private static String displayName(WashSupervisionQueryPort.PendingReassignment value) {
    String name = Stream.of(value.firstName(), value.paternalSurname(), value.maternalSurname())
        .filter(part -> part != null && !part.isBlank())
        .reduce((left, right) -> left + " " + right)
        .orElse(null);
    return name == null || name.isBlank() ? null : name;
  }

  private static ResourceResponse resource(java.util.UUID id, String code, String name) {
    return id == null ? null : new ResourceResponse(id, code, name);
  }
}
