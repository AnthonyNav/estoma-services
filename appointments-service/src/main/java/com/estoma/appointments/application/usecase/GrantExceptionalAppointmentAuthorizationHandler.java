package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.GrantExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.error.DomainViolationException;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class GrantExceptionalAppointmentAuthorizationHandler
    implements GrantExceptionalAppointmentAuthorizationUseCase {
  private final ExceptionalAppointmentAuthorizationRepository authorizations;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public GrantExceptionalAppointmentAuthorizationHandler(
      ExceptionalAppointmentAuthorizationRepository authorizations,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox,
      AuthorizationProjectionRepository authorization) {
    this.authorizations = authorizations;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  @Transactional
  public ApplicationResult grant(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "GrantExceptionalAppointmentAuthorization", ApplicationSupport.fingerprint(
            command.authorizationId(), command.studentAccountId(), command.serviceDate(), command.reason(), command.expiresAt()), now);
    if (gate.isPresent()) return gate.get();
    ApplicationResult result;
    try {
      if (!policy.canManage(command.metadata())) result = ApplicationResult.rejected("FORBIDDEN", "actor cannot grant exceptional authorization");
      else if (command.serviceDate() == null || command.reason() == null || command.reason().isBlank()) {
        result = ApplicationResult.rejected("INVALID_AUTHORIZATION", "service date and reason are required");
      } else if (authorizations.findByStudentAndDate(command.studentAccountId(), command.serviceDate()).isPresent()) {
        result = ApplicationResult.rejected("AUTHORIZATION_ALREADY_EXISTS", "student already has an authorization for that date");
      } else {
        ExceptionalAppointmentAuthorization authorization = ExceptionalAppointmentAuthorization.grant(
            command.authorizationId() == null ? UUID.randomUUID() : command.authorizationId(),
            command.studentAccountId(), command.serviceDate(), command.metadata().actor().accountId(),
            command.reason(), now, command.expiresAt());
        authorizations.save(authorization);
        ApplicationSupport.appendEvent(outbox, command.metadata(), "ExceptionalAppointmentAuthorizationGranted",
            authorization.exceptionalAuthorizationId(), authorization.version(), "{\"authorizationId\":\""
                + authorization.exceptionalAuthorizationId() + "\",\"studentAccountId\":\""
                + authorization.studentAccountId() + "\",\"serviceDate\":\""
                + authorization.serviceDate() + "\"}", now);
        result = ApplicationResult.applied(authorization.exceptionalAuthorizationId(), authorization.version(), "exceptional authorization granted");
      }
    } catch (IllegalArgumentException exception) {
      result = ApplicationResult.rejected("INVALID_AUTHORIZATION", exception.getMessage());
    }
    ApplicationSupport.appendResult(outbox, command.metadata(), result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "GrantExceptionalAppointmentAuthorization", result, now);
    return result;
  }
}
