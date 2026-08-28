package com.estoma.platformbff.application.projection;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/** Typed values accepted by the read-model port. */
public sealed interface ProjectionReadModel permits
    ProjectionReadModel.Person,
    ProjectionReadModel.AuthorizationRole,
    ProjectionReadModel.AuthorizationAccess,
    ProjectionReadModel.AcademicPeriod,
    ProjectionReadModel.StudentProfile,
    ProjectionReadModel.StudentEligibility,
    ProjectionReadModel.CourseSection,
    ProjectionReadModel.Enrollment,
    ProjectionReadModel.StudentImport,
    ProjectionReadModel.ValidationError,
    ProjectionReadModel.MailboxMessage,
    ProjectionReadModel.MailboxVisit,
    ProjectionReadModel.WashCalendar,
    ProjectionReadModel.WashSchedule,
    ProjectionReadModel.AdministrativeClosure,
    ProjectionReadModel.Cabin,
    ProjectionReadModel.Tank,
    ProjectionReadModel.AppointmentSlot,
    ProjectionReadModel.Appointment,
    ProjectionReadModel.ExceptionalAuthorization,
    ProjectionReadModel.WashExecution,
    ProjectionReadModel.ResourceAssignment,
    ProjectionReadModel.ResourceUnavailability,
    ProjectionReadModel.CleaningPeriod,
    ProjectionReadModel.Jornada {

  record Person(
      UUID personId,
      String firstName,
      String paternalSurname,
      String maternalSurname,
      String institutionalEmail,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record AuthorizationRole(
      UUID accountId,
      String roleCode,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record AuthorizationAccess(
      UUID systemAccessId,
      UUID accountId,
      UUID systemId,
      String systemCode,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record AcademicPeriod(
      UUID academicPeriodId,
      String code,
      String name,
      LocalDate startDate,
      LocalDate endDate,
      String status,
      String academicDataStatus,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record StudentProfile(
      UUID studentAcademicProfileId,
      UUID personId,
      UUID accountId,
      String studentNumber,
      Integer currentSemester,
      String academicStatus,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record StudentEligibility(
      UUID eligibilityId,
      UUID studentAcademicProfileId,
      String systemCode,
      boolean eligible,
      String policyVersion,
      long eligibilityVersion,
      long academicProfileVersion,
      Instant evaluatedAt,
      Instant updatedAt) implements ProjectionReadModel {}

  record CourseSection(
      UUID courseSectionId,
      UUID academicPeriodId,
      String nrc,
      String name,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record Enrollment(
      UUID studentAcademicProfileId,
      UUID accountId,
      UUID courseSectionId,
      UUID academicPeriodId,
      String nrc,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record StudentImport(
      UUID importId,
      UUID academicPeriodId,
      String status,
      UUID startedByAccountId,
      UUID authorizedByAccountId,
      Instant startedAt,
      Instant validatedAt,
      Instant applicationStartedAt,
      Instant completedAt,
      Instant failedAt,
      String summary,
      String applicationProgress,
      int validationErrorCount,
      long executionGeneration,
      UUID lastResumedByAccountId,
      Instant lastResumedAt,
      String failedStep,
      String failureReason,
      boolean recoverable,
      long processVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record ValidationError(
      UUID importId,
      long sequence,
      String sheet,
      long row,
      String column,
      String errorCode,
      String message,
      int batchNumber,
      int batchCount,
      long processVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record MailboxMessage(
      UUID inboxMessageId,
      UUID communicationId,
      UUID recipientAccountId,
      String systemCode,
      String communicationType,
      String title,
      String body,
      Instant createdAt,
      Instant expiresAt,
      Instant expiredAt,
      Instant updatedAt) implements ProjectionReadModel {}

  record MailboxVisit(
      UUID accountId,
      String systemCode,
      Instant lastVisitedAt,
      Instant updatedAt) implements ProjectionReadModel {}

  record WashCalendar(
      UUID calendarId,
      String code,
      String name,
      String timezone,
      LocalDate effectiveFrom,
      LocalDate effectiveUntil,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record WashSchedule(
      UUID scheduleId,
      UUID calendarId,
      int dayOfWeek,
      LocalTime openingTime,
      LocalTime closingTime,
      int slotDurationMinutes,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record AdministrativeClosure(
      UUID closureId,
      UUID calendarId,
      String scope,
      UUID cabinId,
      UUID tankId,
      Instant startsAt,
      Instant endsAt,
      String reason,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record Cabin(
      UUID cabinId,
      String code,
      String name,
      String cabinType,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record Tank(
      UUID tankId,
      UUID cabinId,
      String code,
      String name,
      int configuredCapacity,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record AppointmentSlot(
      UUID appointmentTimeSlotId,
      UUID calendarId,
      UUID sourceScheduleId,
      LocalDate serviceDate,
      Instant startsAt,
      Instant endsAt,
      String timezone,
      Instant bookingDeadlineAt,
      Instant cancellationDeadlineAt,
      Instant arrivalToleranceUntil,
      String status,
      Integer capacityNormal,
      Integer capacityJourney,
      Integer capacityImmunocompromised,
      long slotSourceVersion,
      long capacityProjectionVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record Appointment(
      UUID appointmentId,
      UUID appointmentTimeSlotId,
      UUID studentAccountId,
      String appointmentType,
      UUID courseSectionId,
      Integer instrumentCount,
      String pieceType,
      String status,
      String cancellationType,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record ExceptionalAuthorization(
      UUID authorizationId,
      UUID studentAccountId,
      LocalDate serviceDate,
      String status,
      UUID authorizedByAccountId,
      String reason,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record WashExecution(
      UUID washExecutionId,
      UUID appointmentId,
      String appointmentType,
      String status,
      Instant arrivedAt,
      Instant authorizedAt,
      Instant startedAt,
      Instant exitSubmittedAt,
      Instant completedAt,
      Instant cancelledAt,
      String cancellationType,
      Integer submittedPackageCount,
      Integer submittedGreenPaperCassette8Count,
      Integer submittedGreenPaperCassette10Count,
      Integer submittedWitnessTapePortionCount,
      long executionVersion,
      Instant updatedAt) implements ProjectionReadModel {
    public WashExecution(UUID washExecutionId, UUID appointmentId, String appointmentType,
        String status, Instant arrivedAt, Instant authorizedAt, Instant startedAt,
        Instant exitSubmittedAt, Instant completedAt, Instant cancelledAt, String cancellationType,
        long executionVersion, Instant updatedAt) {
      this(washExecutionId, appointmentId, appointmentType, status, arrivedAt, authorizedAt,
          startedAt, exitSubmittedAt, completedAt, cancelledAt, cancellationType,
          null, null, null, null, executionVersion, updatedAt);
    }
  }

  record ResourceAssignment(
      UUID resourceAssignmentId,
      UUID washExecutionId,
      UUID appointmentId,
      UUID appointmentTimeSlotId,
      UUID cabinId,
      UUID tankId,
      String assignmentType,
      Instant assignedAt,
      Instant releasedAt,
      UUID sourceMessageId,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record ResourceUnavailability(
      UUID resourceUnavailabilityId,
      String resourceType,
      UUID resourceId,
      String status,
      String reason,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  record CleaningPeriod(
      UUID cleaningPeriodId,
      UUID washExecutionId,
      UUID resourceAssignmentId,
      UUID cabinId,
      UUID tankId,
      Instant startsAt,
      Instant expectedEndsAt,
      Instant completedAt,
      String status,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}

  /** Prácticas Profesionales — Jornadas catalog, projected read-only from jornadas-service. */
  record Jornada(
      UUID jornadaId,
      UUID tipoJornadaId,
      String nombre,
      LocalDate fecha,
      LocalTime horaInicio,
      LocalTime horaFin,
      String lugar,
      Integer cupoTotal,
      LocalDate fechaLimiteDocumentos,
      String descripcion,
      String estado,
      long sourceVersion,
      Instant updatedAt) implements ProjectionReadModel {}
}
