package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only local inputs for the two student-facing Wash compositions. */
public interface WashStudentQueryPort {
  Optional<Student> findStudent(UUID accountId);

  Optional<UUID> findAccountIdByStudentEnrollment(String studentEnrollment);

  Optional<Appointment> findPrimaryAppointment(UUID accountId, LocalDate serviceDate);

  List<CourseSection> findAvailableCourseSections(UUID accountId);

  record Student(String firstName, String paternalSurname, String maternalSurname,
                 String studentEnrollment, Integer currentSemester) {}

  record Appointment(
      UUID appointmentId,
      String appointmentStatus,
      Long appointmentVersion,
      String appointmentType,
      Integer instrumentCount,
      String pieceType,
      UUID courseSectionId,
      String courseNrc,
      String courseName,
      UUID appointmentTimeSlotId,
      Instant startsAt,
      Instant endsAt,
      String timezone,
      Instant cancellationDeadlineAt,
      Instant arrivalToleranceUntil,
      UUID exceptionalAuthorizationId,
      boolean exceptionalAuthorizationContextKnown,
      UUID washExecutionId,
      String washExecutionStatus,
      Long washExecutionVersion,
      Instant washExecutionArrivedAt,
      String washExecutionRejectionReason,
      Instant exitSubmittedAt,
      Integer submittedPackageCount,
      Integer submittedGreenPaperCassette8Count,
      Integer submittedGreenPaperCassette10Count,
      Integer submittedWitnessTapePortionCount,
      Instant completedAt,
      Integer finalPackageCount,
      Integer finalGreenPaperCassette8Count,
      Integer finalGreenPaperCassette10Count,
      Integer finalWitnessTapePortionCount,
      UUID resourceAssignmentId,
      UUID cabinId,
      String cabinCode,
      String cabinName,
      UUID tankId,
      String tankCode,
      String tankName,
      UUID lastResourceAssignmentId,
      UUID lastCabinId,
      String lastCabinCode,
      String lastCabinName,
      UUID lastTankId,
      String lastTankCode,
      String lastTankName) {}

  record CourseSection(UUID courseSectionId, String nrc, String name) {}
}
