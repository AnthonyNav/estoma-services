package com.estoma.platformbff.contracts.http.wash;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Supervisor view composed from local BFF projections; it carries no owner-wire metadata. */
public record PendingReassignmentsResponse(List<PendingReassignmentResponse> items) {
  public record PendingReassignmentResponse(
      UUID washExecutionId,
      String washExecutionStatus,
      Instant authorizedAt,
      long executionVersion,
      AppointmentResponse appointment,
      StudentResponse student,
      ActiveAssignmentResponse activeResourceAssignment,
      SubmittedExitMaterialsResponse submittedExitMaterials,
      String qrUsageContext) {}

  public record AppointmentResponse(
      UUID appointmentId,
      String appointmentType,
      Integer instrumentCount,
      String pieceType,
      String appointmentStatus,
      CourseSectionResponse courseSectionReference,
      AppointmentTimeSlotResponse appointmentTimeSlot) {}

  public record AppointmentTimeSlotResponse(
      UUID appointmentTimeSlotId,
      LocalDate serviceDate,
      Instant startsAt,
      Instant endsAt,
      String timezone) {}

  public record CourseSectionResponse(UUID courseSectionId, String nrc, String name) {}

  public record StudentResponse(
      UUID accountId,
      UUID personId,
      String displayName,
      String enrollment,
      Integer currentSemester,
      String academicStatus) {}

  public record ActiveAssignmentResponse(
      UUID resourceAssignmentId,
      String assignmentType,
      Instant assignedAt,
      ResourceResponse cabin,
      ResourceResponse tank) {}

  public record ResourceResponse(UUID resourceId, String code, String name) {}

  public record SubmittedExitMaterialsResponse(
      Integer packageCount,
      Integer greenPaperCassette8Count,
      Integer greenPaperCassette10Count,
      Integer witnessTapePortionCount) {}
}
