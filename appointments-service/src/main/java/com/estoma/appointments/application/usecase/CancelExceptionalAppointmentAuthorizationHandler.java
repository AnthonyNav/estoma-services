package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CancelExceptionalAppointmentAuthorizationUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class CancelExceptionalAppointmentAuthorizationHandler
    implements CancelExceptionalAppointmentAuthorizationUseCase {
  private final ExceptionalAppointmentAuthorizationRepository authorizations;
  private final AppointmentRepository appointments;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public CancelExceptionalAppointmentAuthorizationHandler(
      ExceptionalAppointmentAuthorizationRepository authorizations, AppointmentRepository appointments,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox,
      AuthorizationProjectionRepository authorization) {
    this.authorizations = authorizations;
    this.appointments = appointments;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  @Transactional
  public ApplicationResult cancel(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claimClient(idempotency, outbox,
        command.metadata(), "CancelExceptionalAppointmentAuthorization",
        ApplicationSupport.fingerprint(command.authorizationId(), command.reason()), now);
    if (gate.isPresent()) return gate.get();

    ApplicationResult result;
    try {
      var current = authorizations.findByIdForUpdate(command.authorizationId()).orElse(null);
      if (current == null) {
        result = ApplicationResult.rejected("AUTHORIZATION_NOT_FOUND", "authorization was not found");
      } else if (!policy.canGrantExceptionalAuthorization(command.metadata())) {
        result = ApplicationResult.rejected("FORBIDDEN",
            "only an authorized wash supervisor can cancel exceptional authorization");
      } else if (appointments.findActiveByExceptionalAuthorizationForUpdate(command.authorizationId()).isPresent()) {
        result = ApplicationResult.rejected("AUTHORIZATION_IN_USE",
            "an appointment still consumes this authorization");
      } else if (current.isCancelled()) {
        result = ApplicationResult.applied(current.exceptionalAuthorizationId(), current.version(),
            "authorization already cancelled");
      } else {
        var cancelled = current.cancel(command.metadata().actor().accountId(), command.reason(), now);
        if (!authorizations.updateIfVersion(cancelled, current.version())) {
          result = ApplicationResult.rejected("CONCURRENT_MODIFICATION",
              "authorization changed while cancelling");
        } else {
          ApplicationSupport.appendEvent(outbox, command.metadata(),
              "ExceptionalAppointmentAuthorizationCancelled", cancelled.exceptionalAuthorizationId(),
              cancelled.version(), "{\"authorizationId\":\""
                  + cancelled.exceptionalAuthorizationId() + "\"}", now);
          result = ApplicationResult.applied(cancelled.exceptionalAuthorizationId(),
              cancelled.version(), "authorization cancelled");
        }
      }
    } catch (IllegalArgumentException exception) {
      result = ApplicationResult.rejected("INVALID_AUTHORIZATION", exception.getMessage());
    }

    ApplicationSupport.appendResult(outbox, command.metadata(),
        "CancelExceptionalAppointmentAuthorization", result, now);
    ApplicationSupport.complete(idempotency, command.metadata(),
        "CancelExceptionalAppointmentAuthorization", result, now);
    return result;
  }
}
