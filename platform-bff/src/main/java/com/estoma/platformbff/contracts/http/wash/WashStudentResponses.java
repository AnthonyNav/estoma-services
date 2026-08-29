package com.estoma.platformbff.contracts.http.wash;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Public DTOs for WASH-STU-01 and WASH-STU-02. */
public final class WashStudentResponses {
  private WashStudentResponses() {}

  public record StudentHomeResponse(StudentHomeStudent student, LocalDate serviceDate,
                                    StudentHomeAppointment appointment) {}

  public record StudentHomeStudent(String firstName, String fullName, String studentEnrollment,
                                  Integer currentSemester) {}

  public record StudentHomeAppointment(
      UUID appointmentId,
      String appointmentStatus,
      String appointmentType,
      Integer instrumentCount,
      String pieceType,
      CourseSection courseSection,
      AppointmentTimeSlot timeSlot,
      WashExecution washExecution,
      String qrUsageContext,
      String qrRepresentation) {}

  public record CourseSection(UUID courseSectionId, String nrc, String name) {}

  public record AppointmentTimeSlot(UUID appointmentTimeSlotId, Instant startsAt, Instant endsAt,
                                    String timezone) {}

  public record WashExecution(UUID washExecutionId, String status) {}

  /** Flat shape is the canonical WASH-STU-02 contract. */
  public record AppointmentFormContextResponse(
      String studentFullName,
      String studentEnrollment,
      Integer currentSemester,
      List<CourseSection> availableCourseSections) {}
}
