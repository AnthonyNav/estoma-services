package com.estoma.platformbff.contracts.http.wash;

import com.estoma.platformbff.contracts.http.Required;
import com.estoma.platformbff.contracts.http.RequiredNullable;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Supervisor view composed from local BFF projections; it carries no owner-wire metadata. */
public record PendingReassignmentsResponse(@Required List<PendingReassignmentResponse> items) {
  public record PendingReassignmentResponse(
      @Required UUID washExecutionId,
      @Required String washExecutionStatus,
      @RequiredNullable Instant authorizedAt,
      long executionVersion,
      @Required AppointmentResponse appointment,
      @Required StudentResponse student,
      @RequiredNullable ActiveAssignmentResponse activeResourceAssignment,
      @RequiredNullable SubmittedExitMaterialsResponse submittedExitMaterials,
      @Required String qrUsageContext) {}

  public record AppointmentResponse(
      @Required UUID appointmentId,
      @Required String appointmentType,
      @RequiredNullable Integer instrumentCount,
      @RequiredNullable String pieceType,
      @Required String appointmentStatus,
      @RequiredNullable CourseSectionResponse courseSectionReference,
      @Required AppointmentTimeSlotResponse appointmentTimeSlot) {}

  public record AppointmentTimeSlotResponse(
      @Required UUID appointmentTimeSlotId,
      @Required LocalDate serviceDate,
      @Required Instant startsAt,
      @Required Instant endsAt,
      @Required String timezone) {}

  public record CourseSectionResponse(
      @Required UUID courseSectionId,
      @RequiredNullable String nrc,
      @RequiredNullable String name) {}

  public record StudentResponse(
      @Required UUID accountId,
      @Required UUID personId,
      @Required String displayName,
      @Required String enrollment,
      @Required Integer currentSemester,
      @RequiredNullable String academicStatus) {}

  public record ActiveAssignmentResponse(
      @Required UUID resourceAssignmentId,
      @Required String assignmentType,
      @Required Instant assignedAt,
      @Required ResourceResponse cabin,
      @Required ResourceResponse tank) {}

  public record ResourceResponse(
      @Required UUID resourceId,
      @Required String code,
      @Required String name) {}

  public record SubmittedExitMaterialsResponse(
      @Required Integer packageCount,
      @Required Integer greenPaperCassette8Count,
      @Required Integer greenPaperCassette10Count,
      @Required Integer witnessTapePortionCount) {}
}
