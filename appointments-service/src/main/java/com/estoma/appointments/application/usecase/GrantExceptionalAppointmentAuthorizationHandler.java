package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.GrantExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GrantExceptionalAppointmentAuthorizationHandler
    implements GrantExceptionalAppointmentAuthorizationUseCase {
  private final ExceptionalAppointmentAuthorizationRepository authorizations;
  private final AcademicProjectionRepository academic;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public GrantExceptionalAppointmentAuthorizationHandler(
      ExceptionalAppointmentAuthorizationRepository authorizations,
      AcademicProjectionRepository academic, CommandIdempotencyRepository idempotency,
      OutboxRepository outbox, AuthorizationProjectionRepository authorization) {
    this.authorizations = authorizations;
    this.academic = academic;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  @Transactional
  public ApplicationResult grant(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claimClient(idempotency, outbox, command.metadata(),
        "GrantExceptionalAppointmentAuthorization", ApplicationSupport.fingerprint(
            command.authorizationId(), command.studentAccountId(), command.serviceDate(),
            command.reason(), command.expiresAt()), now);
    if (gate.isPresent()) return gate.get();

    ApplicationResult result;
    try {
      if (!policy.canGrantExceptionalAuthorization(command.metadata())) {
        result = ApplicationResult.rejected("FORBIDDEN",
            "only an authorized wash supervisor can grant exceptional authorization");
      } else if (command.serviceDate() == null || command.reason() == null || command.reason().isBlank()) {
        result = ApplicationResult.rejected("INVALID_AUTHORIZATION", "service date and reason are required");
      } else if (!academic.reliable()) {
        result = ApplicationResult.rejected("ACADEMIC_PROJECTION_UNRELIABLE",
            "student academic state cannot be trusted");
      } else if (academic.findStudentProfile(command.studentAccountId())
          .filter(profile -> profile.reliable() && "ACTIVE".equals(profile.academicStatus())).isEmpty()) {
        result = ApplicationResult.rejected("STUDENT_NOT_ACTIVE", "student must have an active academic profile");
      } else if (authorizations.findByStudentAndDate(
          command.studentAccountId(), command.serviceDate()).isPresent()) {
        result = ApplicationResult.rejected("AUTHORIZATION_ALREADY_EXISTS",
            "student already has an authorization for that date");
      } else {
        ExceptionalAppointmentAuthorization authorization = ExceptionalAppointmentAuthorization.grant(
            command.authorizationId() == null ? UUID.randomUUID() : command.authorizationId(),
            command.studentAccountId(), command.serviceDate(), command.metadata().actor().accountId(),
            command.reason(), now, command.expiresAt());
        authorizations.save(authorization);
        ApplicationSupport.appendEvent(outbox, command.metadata(),
            "ExceptionalAppointmentAuthorizationGranted", authorization.exceptionalAuthorizationId(),
            authorization.version(), AppointmentEventPayloads.authorizationGranted(authorization), now);
        result = ApplicationResult.applied(authorization.exceptionalAuthorizationId(),
            authorization.version(), "exceptional authorization granted");
      }
    } catch (IllegalArgumentException exception) {
      result = ApplicationResult.rejected("INVALID_AUTHORIZATION", exception.getMessage());
    }

    ApplicationSupport.appendResult(outbox, command.metadata(),
        "GrantExceptionalAppointmentAuthorization", result, now);
    ApplicationSupport.complete(idempotency, command.metadata(),
        "GrantExceptionalAppointmentAuthorization", result, now);
    return result;
  }
}
