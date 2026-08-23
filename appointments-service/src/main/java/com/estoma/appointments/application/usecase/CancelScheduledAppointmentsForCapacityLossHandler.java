package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CancelScheduledAppointmentsForCapacityLossUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.model.Appointment;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class CancelScheduledAppointmentsForCapacityLossHandler
    implements CancelScheduledAppointmentsForCapacityLossUseCase {
  private final AppointmentRepository appointments;
  private final ExceptionalAppointmentAuthorizationRepository authorizations;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;

  public CancelScheduledAppointmentsForCapacityLossHandler(AppointmentRepository appointments,
      ExceptionalAppointmentAuthorizationRepository authorizations,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox) {
    this.appointments = appointments;
    this.authorizations = authorizations;
    this.idempotency = idempotency;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public ApplicationResult cancel(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "CancelScheduledAppointmentsForCapacityLoss", ApplicationSupport.fingerprint(command.sourceEventId(),
            command.slotId(), command.availableCapacity()), now);
    if (gate.isPresent()) return gate.get();
    var all = appointments.findForSlot(command.slotId(), true);
    int activeDemand = (int) all.stream().filter(Appointment::isActive).count();
    int cancelled = 0;
    for (Appointment candidate : all) {
      if (activeDemand <= command.availableCapacity()) break;
      if (!candidate.status().name().equals("SCHEDULED") || candidate.entryAuthorizedAt() != null) continue;
      var current = appointments.findByIdForUpdate(candidate.appointmentId()).orElse(candidate);
      var changed = current.cancel(CancellationType.CLINIC, CancellationSubreason.CAPACITY_LOSS,
          "capacity loss", command.metadata().actor().accountId(), now);
      if (!appointments.updateIfVersion(changed, current.version())) continue;
      activeDemand--;
      cancelled++;
      if (changed.exceptionalAuthorizationId() != null) {
        authorizations.findByIdForUpdate(changed.exceptionalAuthorizationId());
      }
      ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentCancelled",
          changed.appointmentId(), changed.version(), "{\"appointmentId\":\""
              + changed.appointmentId() + "\",\"cancellationType\":\"CLINIC\",\"subreason\":\"CAPACITY_LOSS\"}", now);
    }
    ApplicationResult result = ApplicationResult.applied(command.slotId(), 0,
        "capacity loss cancelled " + cancelled + " scheduled appointments");
    ApplicationSupport.appendResult(outbox, command.metadata(), result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "CancelScheduledAppointmentsForCapacityLoss", result, now);
    return result;
  }
}
