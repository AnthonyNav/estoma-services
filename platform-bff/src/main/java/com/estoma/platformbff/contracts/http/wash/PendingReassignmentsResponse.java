package com.estoma.platformbff.contracts.http.wash;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Supervisor view composed from local BFF projections; it carries no owner-wire metadata. */
public record PendingReassignmentsResponse(List<PendingReassignmentResponse> items) {
  public record PendingReassignmentResponse(
      UUID washExecutionId,
      String status,
      Instant authorizedAt,
      long executionVersion,
      AppointmentResponse appointment,
      StudentResponse student,
      ActiveAssignmentResponse activeAssignment,
      QrUsageContextResponse qrUsageContext) {}

  public record AppointmentResponse(
      UUID appointmentId,
      UUID appointmentTimeSlotId,
      String appointmentType,
      String status,
      CourseSectionResponse courseSection) {}

  public record CourseSectionResponse(UUID courseSectionId, String nrc, String name) {}

  public record StudentResponse(
      UUID accountId,
      UUID personId,
      String firstName,
      String paternalSurname,
      String maternalSurname,
      String studentNumber,
      Integer currentSemester,
      String academicStatus) {}

  public record ActiveAssignmentResponse(
      UUID resourceAssignmentId,
      String assignmentType,
      Instant assignedAt,
      ResourceResponse cabin,
      ResourceResponse tank) {}

  public record ResourceResponse(UUID resourceId, String code, String name) {}

  /** A view-only context: the canonical appointment id remains the only QR identity. */
  public record QrUsageContextResponse(UUID appointmentId) {}
}
