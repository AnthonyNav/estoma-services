package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only queries over the typed BFF projections. This port deliberately has no owner RPC
 * operations: public views must be assembled from the local projection database only.
 */
public interface ProjectionQueryPort {
  List<MailboxMessage> findVisibleMailboxMessages(UUID recipientAccountId);

  List<MailboxSummary> summarizeMailbox(UUID recipientAccountId);

  Optional<String> findRoleCode(UUID accountId);

  boolean hasActiveSystemAccess(UUID accountId, String systemCode);

  Optional<UUID> findAppointmentStudentAccountId(UUID appointmentId);

  Optional<UUID> findWashExecutionStudentAccountId(UUID washExecutionId);

  List<PendingReassignment> findPendingReassignments();

  record MailboxMessage(
      UUID inboxMessageId,
      UUID communicationId,
      String systemCode,
      String communicationType,
      String title,
      String body,
      Instant createdAt,
      Instant expiresAt) {}

  record MailboxSummary(
      String systemCode,
      long messageCount,
      long newMessageCount,
      Instant lastVisitedAt) {}

  /** A denormalized row used only to assemble the supervisor view at the HTTP boundary. */
  record PendingReassignment(
      UUID washExecutionId,
      String executionStatus,
      Instant authorizedAt,
      long executionVersion,
      UUID appointmentId,
      UUID appointmentTimeSlotId,
      String appointmentType,
      String appointmentStatus,
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
      String tankName) {}
}
