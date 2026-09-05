package com.estoma.platformbff.contracts.http.wash;

import com.estoma.platformbff.contracts.http.Required;
import com.estoma.platformbff.contracts.http.RequiredNullable;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public DTOs for WASH-STU-01 and WASH-STU-02. */
public final class WashStudentResponses {
  private WashStudentResponses() {}

  public record StudentHomeResponse(@NotNull StudentHomeStudent student, @NotNull LocalDate serviceDate,
                                    StudentHomeAppointment appointment) {}

  public record StudentHomeStudent(@NotNull String firstName, @NotNull String fullName, @NotNull String studentEnrollment,
                                  Integer currentSemester) {}

  public record StudentHomeAppointment(
      @NotNull UUID appointmentId,
      @NotNull @Pattern(regexp = "SCHEDULED|CANCELLED|MISSED|ENTRY_REJECTED|IN_PROGRESS|COMPLETED") String appointmentStatus,
      long appointmentVersion,
      boolean usesExceptionalAuthorization,
      @Required StudentCancellationAction studentCancellationAction,
      @NotNull @Pattern(regexp = "NORMAL|JOURNEY|IMMUNOCOMPROMISED") String appointmentType,
      Integer instrumentCount,
      @Pattern(regexp = "HIGH_SPEED|LOW_SPEED|CONTRA_ANGLE") String pieceType,
      CourseSection courseSection,
      AppointmentTimeSlot timeSlot,
      WashExecution washExecution,
      @NotNull @Pattern(regexp = "ENTRY|STUDENT_EXIT|SUPERVISOR_EXIT_REVIEW|NONE") String qrUsageContext,
      String qrRepresentation) {}

  public enum StudentCancellationAction {
    AVAILABLE,
    DEADLINE_PASSED,
    NOT_APPLICABLE
  }

  public record CourseSection(UUID courseSectionId, String nrc, String name) {}

  public record AppointmentTimeSlot(UUID appointmentTimeSlotId, Instant startsAt, Instant endsAt,
                                    String timezone, Instant cancellationDeadlineAt) {}

  /** Execution state intentionally matches the student and supervisor front-end read models. */
  public record WashExecution(
      @NotNull UUID washExecutionId,
      @NotNull @Pattern(regexp = "PENDING_ENTRY|ENTRY_REJECTED|PENDING_REASSIGNMENT|IN_PROGRESS|EXIT_SUBMITTED|COMPLETED|CANCELLED") String status,
      Long version,
      Instant arrivedAt,
      String rejectionReason,
      ActiveResourceAssignment activeResourceAssignment) {}

  public record ActiveResourceAssignment(
      UUID resourceAssignmentId,
      UUID cabinId,
      String cabinCode,
      String cabinName,
      UUID tankId,
      String tankCode,
      String tankName) {}

  /** Canonical WASH-STU-02 shape consumed by estoma-app. */
  public record AppointmentFormContextResponse(
      FormStudent student,
      List<CourseSection> availableCourseSections) {}

  public record FormStudent(@NotNull String fullName, @NotNull String studentEnrollment,
                            @NotNull Integer currentSemester) {}

  public enum AppointmentType { NORMAL, JOURNEY, IMMUNOCOMPROMISED }

  public enum PieceType { HIGH_SPEED, LOW_SPEED, CONTRA_ANGLE }

  public enum AvailabilityBlockingReason {
    ACTIVE_APPOINTMENT_EXISTS,
    STUDENT_BLOCKED,
    DAILY_COMPLETED_LIMIT,
    EXCEPTIONAL_AUTHORIZATION_REQUIRED,
    ACADEMIC_ENROLLMENT_INVALID,
    STUDENT_ACCESS_DENIED
  }

  /** Owner-derived availability photograph. Reading it never reserves capacity. */
  public record AppointmentAvailabilityResponse(
      @Required boolean canSchedule,
      @Required List<AvailabilityBlockingReason> blockingReasons,
      @Required int dailyPenaltyPoints,
      @Required int dailyCompletedAppointments,
      @Required boolean exceptionalAuthorizationRequired,
      @Required boolean exceptionalAuthorizationAvailable,
      @RequiredNullable UUID exceptionalAuthorizationId,
      @Required List<AvailableAppointmentTimeSlot> availableTimeSlots) {}

  public record AvailableAppointmentTimeSlot(
      @Required UUID appointmentTimeSlotId,
      @Required Instant startsAt,
      @Required Instant endsAt,
      @Required @jakarta.validation.constraints.Positive int availableCapacity,
      @Required Instant bookingDeadlineAt) {}

  /** Side-effect-free WASH-STU-03 manual supervisor lookup response. */
  public record SupervisorEntryLookupResponse(
      LocalDate serviceDate,
      FormStudent student,
      StudentHomeAppointment appointment,
      WashExecution washExecution) {}
}
