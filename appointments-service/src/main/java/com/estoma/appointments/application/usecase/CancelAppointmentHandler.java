package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.port.in.CancelAppointmentUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.domain.error.DomainViolationException;
import com.estoma.appointments.domain.model.CancellationSubreason;
import com.estoma.appointments.domain.model.CancellationType;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class CancelAppointmentHandler implements CancelAppointmentUseCase {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public CancelAppointmentHandler(AppointmentRepository appointments, AppointmentTimeSlotRepository slots,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox,
      AuthorizationProjectionRepository authorization) {
    this.appointments = appointments;
    this.slots = slots;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  @Transactional
  public ApplicationResult cancel(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "CancelAppointment", ApplicationSupport.fingerprint(command.appointmentId(),
            command.clinicCancellation(), command.reason()), now);
    if (gate.isPresent()) return gate.get();
    ApplicationResult result;
    try {
      var current = appointments.findById(command.appointmentId()).orElse(null);
      if (current == null) result = ApplicationResult.rejected("APPOINTMENT_NOT_FOUND", "appointment was not found");
      else if (command.clinicCancellation()
          ? !policy.canManage(command.metadata())
          : !policy.canCancelOwn(command.metadata(), current.studentAccountId())) {
        result = ApplicationResult.rejected("FORBIDDEN", "actor cannot cancel this appointment");
      } else {
        var slot = slots.findByIdForUpdate(current.appointmentTimeSlotId()).orElse(null);
        if (slot == null) result = ApplicationResult.rejected("SLOT_NOT_FOUND", "appointment slot was not found");
        else if (!command.clinicCancellation() && now.isAfter(slot.cancellationDeadlineAt())) {
          result = ApplicationResult.rejected("CANCELLATION_DEADLINE_PASSED", "student cancellation deadline has passed");
        } else {
          current = appointments.findByIdForUpdate(current.appointmentId()).orElseThrow();
          var cancelled = command.clinicCancellation()
              ? current.cancel(CancellationType.CLINIC, CancellationSubreason.CAPACITY_LOSS,
                  command.reason(), command.metadata().actor().accountId(), now)
              : current.cancel(CancellationType.STUDENT, null, command.reason(),
                  command.metadata().actor().accountId(), now);
          if (!appointments.updateIfVersion(cancelled, current.version())) {
            result = ApplicationResult.rejected("CONCURRENT_MODIFICATION", "appointment changed while cancelling");
          } else {
            ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentCancelled",
                cancelled.appointmentId(), cancelled.version(), "{\"appointmentId\":\""
                    + cancelled.appointmentId() + "\",\"cancellationType\":\""
                    + cancelled.cancellationType() + "\",\"subreason\":\""
                    + cancelled.cancellationSubreason() + "\"}", now);
            result = ApplicationResult.applied(cancelled.appointmentId(), cancelled.version(), "appointment cancelled");
          }
        }
      }
    } catch (IllegalArgumentException exception) {
      result = ApplicationResult.rejected("INVALID_CANCELLATION", exception.getMessage());
    }
    ApplicationSupport.appendResult(outbox, command.metadata(), result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "CancelAppointment", result, now);
    return result;
  }
}
