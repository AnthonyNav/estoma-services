package com.estoma.platformbff.application.port;

import com.estoma.platformbff.application.projection.ProjectionReadModel;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only queries over the typed BFF projections. This port deliberately has no owner RPC
 * operations: public views must be assembled from the local projection database only.
 */
public interface ProjectionQueryPort {
  MailboxPage findVisibleMailboxMessages(UUID recipientAccountId, String systemCode, int limit,
      MailboxCursor cursor);

  MailboxSummary summarizeMailbox(UUID recipientAccountId, String systemCode);

  Optional<PersonPresentation> findPersonPresentation(UUID personId);

  Optional<AccountPresentation> findAccountPresentation(UUID accountId);

  /** Active access plus a trusted, active catalog row; eligibility remains an application rule. */
  List<String> findActiveSystemCandidates(UUID accountId);

  boolean hasEligibleAcademicSystem(UUID accountId, String systemCode);

  Optional<String> findRoleCode(UUID accountId);

  boolean hasActiveSystemAccess(UUID accountId, String systemCode);

  Optional<UUID> findAppointmentStudentAccountId(UUID appointmentId);

  Optional<UUID> findWashExecutionStudentAccountId(UUID washExecutionId);

  List<PendingReassignment> findPendingReassignments();

  /** Publicly visible Jornadas catalog (Prácticas Profesionales) — no per-account scoping. */
  List<ProjectionReadModel.Jornada> findJornadas();

  /** A given Alumno's own Registros (Prácticas Profesionales), most recently updated first. */
  List<ProjectionReadModel.Registro> findRegistrosByAlumno(UUID alumnoAccountId);

  /** All Registros for a Jornada, in registration order — the Pasante's validation queue. */
  List<ProjectionReadModel.Registro> findRegistrosByJornada(UUID jornadaId);

  Optional<UUID> findRegistroAlumnoAccountId(UUID registroId);

  /** The Alumno's own current semester (academic-service, projected) — never client-supplied. */
  Optional<Integer> findCurrentSemester(UUID accountId);

  /** Publicly visible Tipos de Jornada catalog — no per-account scoping. */
  List<ProjectionReadModel.TipoJornada> findTiposJornada();

  record MailboxMessage(
      UUID inboxMessageId,
      UUID communicationId,
      String systemCode,
      String communicationType,
      String title,
      String body,
      Instant createdAt,
      Instant expiresAt) {}

  record MailboxCursor(Instant createdAt, UUID inboxMessageId) {}

  record MailboxPage(List<MailboxMessage> items, MailboxCursor nextCursor) {}

  record MailboxSummary(
      String systemCode,
      long messageCount,
      long newMessageCount,
      Instant lastVisitedAt) {}

  record PersonPresentation(
      UUID personId,
      String firstName,
      String paternalSurname,
      String maternalSurname,
      String institutionalEmail,
      String status) {}

  record AccountPresentation(
      UUID accountId,
      UUID personId,
      String loginType,
      String loginIdentifier,
      String accountStatus) {}

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
