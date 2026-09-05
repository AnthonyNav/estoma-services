package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Read-only composition input dedicated to the supervisor wash view. */
public interface WashSupervisionQueryPort {
  HomeSnapshot summarizeHome(LocalDate serviceDate);

  List<LookupCandidate> findAppointments(UUID studentAccountId, LocalDate serviceDate);

  List<PendingReassignment> findPendingReassignments();

  record HomeSnapshot(
      long registeredAppointments,
      long inProcessAppointments,
      long completedAppointments,
      long deniedAppointments,
      long cancelledAppointments,
      long pendingReassignmentsCount) {}

  record LookupCandidate(
      UUID studentAccountId,
      UUID personId,
      String firstName,
      String paternalSurname,
      String maternalSurname,
      String studentNumber,
      Integer currentSemester,
      String academicStatus,
      UUID appointmentId,
      String appointmentStatus,
      String appointmentType,
      Integer instrumentCount,
      String pieceType,
      UUID courseSectionId,
      String courseNrc,
      String courseName,
      UUID appointmentTimeSlotId,
      Instant slotStartsAt,
      Instant slotEndsAt,
      String slotTimezone,
      UUID washExecutionId,
      String executionStatus,
      Long executionVersion,
      Instant arrivedAt,
      String rejectionReason,
      Instant exitSubmittedAt,
      UUID resourceAssignmentId,
      String assignmentType,
      UUID cabinId,
      String cabinCode,
      String cabinName,
      UUID tankId,
      String tankCode,
      String tankName,
      Integer submittedPackageCount,
      Integer submittedGreenPaperCassette8Count,
      Integer submittedGreenPaperCassette10Count,
      Integer submittedWitnessTapePortionCount) {}

  record PendingReassignment(
      UUID washExecutionId,
      String executionStatus,
      Instant authorizedAt,
      long executionVersion,
      UUID appointmentId,
      String appointmentType,
      Integer instrumentCount,
      String pieceType,
      String appointmentStatus,
      UUID appointmentTimeSlotId,
      LocalDate serviceDate,
      Instant slotStartsAt,
      Instant slotEndsAt,
      String slotTimezone,
      UUID courseSectionId,
      String courseNrc,
      String courseName,
      UUID studentAccountId,
      UUID personId,
      String firstName,
      String paternalSurname,
      String maternalSurname,
      String studentNumber,
      Integer currentSemester,
      String academicStatus,
      UUID resourceAssignmentId,
      String assignmentType,
      Instant assignedAt,
      UUID cabinId,
      String cabinCode,
      String cabinName,
      UUID tankId,
      String tankCode,
      String tankName,
      Integer submittedPackageCount,
      Integer submittedGreenPaperCassette8Count,
      Integer submittedGreenPaperCassette10Count,
      Integer submittedWitnessTapePortionCount) {}
}
