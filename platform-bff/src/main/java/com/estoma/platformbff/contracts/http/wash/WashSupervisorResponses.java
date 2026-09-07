package com.estoma.platformbff.contracts.http.wash;

import com.estoma.platformbff.contracts.http.Required;
import com.estoma.platformbff.contracts.http.RequiredNullable;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Public, presentation-oriented read models for the supervisor Home and unified lookup. */
public final class WashSupervisorResponses {
  private WashSupervisorResponses() {}

  public record SupervisorHomeResponse(
      @Required LocalDate serviceDate,
      @Required SupervisorHomeSummary summary,
      long pendingReassignmentsCount) {}

  public record SupervisorHomeSummary(
      long registeredAppointments,
      long inProcessAppointments,
      long completedAppointments,
      long deniedAppointments,
      long cancelledAppointments) {}

  public enum NextAction {
    ENTRY,
    ENTRY_DECISION,
    REASSIGNMENT,
    EXIT_REVIEW,
    NONE
  }

  public record SupervisorLookupResponse(
      @Required LocalDate serviceDate,
      @Required NextAction nextAction,
      @Required SupervisorStudent student,
      @Required SupervisorAppointment appointment,
      SupervisorWashExecution washExecution,
      ActiveResourceAssignment activeResourceAssignment) {}

  /** Exact, read-only supervisor view of one current or historical Wash execution. */
  public record SupervisorExecutionDetailResponse(
      @Required LocalDate serviceDate,
      @Required SupervisorStudent student,
      @Required SupervisorExecutionAppointment appointment,
      @Required SupervisorExecution washExecution) {}

  public record SupervisorExecutionAppointment(
      @Required UUID appointmentId,
      @Required Long appointmentVersion,
      @Required @Pattern(regexp = "SCHEDULED|CANCELLED|MISSED|ENTRY_REJECTED|IN_PROGRESS|COMPLETED")
      String appointmentStatus,
      @Required @Pattern(regexp = "NORMAL|JOURNEY|IMMUNOCOMPROMISED") String appointmentType,
      @RequiredNullable @Positive Integer instrumentCount,
      @RequiredNullable @Pattern(regexp = "HIGH_SPEED|LOW_SPEED|CONTRA_ANGLE") String pieceType,
      @RequiredNullable SupervisorExecutionCourseSection courseSectionReference,
      @Required AppointmentTimeSlot appointmentTimeSlot) {}

  public record SupervisorExecutionCourseSection(
      @Required UUID courseSectionId,
      @RequiredNullable String nrc,
      @RequiredNullable String name) {}

  public record SupervisorExecution(
      @Required UUID washExecutionId,
      @Required @Pattern(regexp = "PENDING_ENTRY|ENTRY_REJECTED|PENDING_REASSIGNMENT|IN_PROGRESS|EXIT_SUBMITTED|COMPLETED|CANCELLED")
      String status,
      @Required Long version,
      @Required Long executionVersion,
      @Required Instant arrivedAt,
      @RequiredNullable String rejectionReason,
      @RequiredNullable Instant exitSubmittedAt,
      @RequiredNullable SubmittedExitMaterials submittedExitMaterials,
      @RequiredNullable Instant completedAt,
      @RequiredNullable SubmittedExitMaterials finalExitMaterials,
      @RequiredNullable ActiveResourceAssignment activeResourceAssignment,
      @RequiredNullable ActiveResourceAssignment lastResourceAssignment) {}

  public record SupervisorStudent(
      @Required UUID studentAccountId,
      @Required String displayName,
      @Required String studentEnrollment,
      @Required @Positive Integer currentSemester) {}

  public record SupervisorAppointment(
      @Required UUID appointmentId,
      @Required @Pattern(regexp = "SCHEDULED|CANCELLED|MISSED|ENTRY_REJECTED|IN_PROGRESS|COMPLETED")
      String appointmentStatus,
      @Required @Pattern(regexp = "NORMAL|JOURNEY|IMMUNOCOMPROMISED") String appointmentType,
      @Required @Positive Integer instrumentCount,
      @Required @Pattern(regexp = "HIGH_SPEED|LOW_SPEED|CONTRA_ANGLE") String pieceType,
      @Required CourseSectionReference courseSectionReference,
      @Required AppointmentTimeSlot appointmentTimeSlot) {}

  public record CourseSectionReference(
      @Required UUID courseSectionId,
      @Required String nrc,
      @Required String name) {}

  public record AppointmentTimeSlot(
      @Required UUID appointmentTimeSlotId,
      @Required Instant startsAt,
      @Required Instant endsAt,
      @Required String timezone) {}

  public record SupervisorWashExecution(
      @Required UUID washExecutionId,
      @Required @Pattern(regexp = "PENDING_ENTRY|ENTRY_REJECTED|PENDING_REASSIGNMENT|IN_PROGRESS|EXIT_SUBMITTED|COMPLETED|CANCELLED")
      String status,
      @Required Long executionVersion,
      @Required Instant arrivedAt,
      String rejectionReason,
      Instant exitSubmittedAt,
      SubmittedExitMaterials submittedExitMaterials) {}

  public record SubmittedExitMaterials(
      int packageCount,
      int greenPaperCassette8Count,
      int greenPaperCassette10Count,
      int witnessTapePortionCount) {}

  public record ActiveResourceAssignment(
      @Required UUID resourceAssignmentId,
      @Required String assignmentType,
      @Required Resource cabin,
      @Required Resource tank) {}

  public record Resource(
      @Required UUID resourceId,
      @Required String code,
      @Required String name) {}
}
