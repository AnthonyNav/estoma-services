package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.ActiveAssignmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.AppointmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.CourseSectionResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.PendingReassignmentResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.QrUsageContextResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.ResourceResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse.StudentResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class WashSupervisionQueryService {
  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final ProjectionQueryPort projections;

  public WashSupervisionQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, ProjectionQueryPort projections) {
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

  private PendingReassignmentResponse compose(ProjectionQueryPort.PendingReassignment value) {
    CourseSectionResponse course = value.courseSectionId() == null ? null
        : new CourseSectionResponse(value.courseSectionId(), value.courseNrc(), value.courseName());
    AppointmentResponse appointment = value.appointmentId() == null ? null
        : new AppointmentResponse(value.appointmentId(), value.appointmentTimeSlotId(),
            value.appointmentType(), value.appointmentStatus(), course);
    StudentResponse student = value.studentAccountId() == null ? null
        : new StudentResponse(value.studentAccountId(), value.personId(), value.firstName(),
            value.paternalSurname(), value.maternalSurname(), value.studentNumber(),
            value.currentSemester(), value.academicStatus());
    ActiveAssignmentResponse assignment = value.resourceAssignmentId() == null ? null
        : new ActiveAssignmentResponse(value.resourceAssignmentId(), value.assignmentType(),
            value.assignedAt(), resource(value.cabinId(), value.cabinCode(), value.cabinName()),
            resource(value.tankId(), value.tankCode(), value.tankName()));
    // This is deliberately derived during response composition rather than stored in a read model.
    QrUsageContextResponse qr = value.appointmentId() == null ? null
        : new QrUsageContextResponse(value.appointmentId());
    return new PendingReassignmentResponse(value.washExecutionId(), value.executionStatus(),
        value.authorizedAt(), value.executionVersion(), appointment, student, assignment, qr);
  }

  private static ResourceResponse resource(java.util.UUID id, String code, String name) {
    return id == null ? null : new ResourceResponse(id, code, name);
  }
}
