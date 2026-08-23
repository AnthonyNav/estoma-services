package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.OutboxIntent;
import com.estoma.appointments.application.port.in.CreateAppointmentTimeSlotUseCase;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.domain.model.AppointmentTimeSlot;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public final class CreateAppointmentTimeSlotHandler implements CreateAppointmentTimeSlotUseCase {
  private final AppointmentTimeSlotRepository slots;
  private final WashAdministrationProjectionRepository administration;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;
  private final LocalAuthorizationPolicy policy;

  public CreateAppointmentTimeSlotHandler(AppointmentTimeSlotRepository slots,
      WashAdministrationProjectionRepository administration,
      CommandIdempotencyRepository idempotency, OutboxRepository outbox,
      AuthorizationProjectionRepository authorization) {
    this.slots = slots;
    this.administration = administration;
    this.idempotency = idempotency;
    this.outbox = outbox;
    this.policy = new LocalAuthorizationPolicy(authorization);
  }

  @Override
  @Transactional
  public ApplicationResult create(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "CreateAppointmentTimeSlot", ApplicationSupport.fingerprint(command.slotId(),
            command.sourceScheduleId(), command.serviceDate(), command.startsAt(), command.endsAt()), now);
    if (gate.isPresent()) return gate.get();

    ApplicationResult result;
    try {
      boolean authorized = policy.canAdministerSlots(command.metadata())
          || policy.isInternalAppointmentsProcess(command.metadata());
      if (!authorized) {
        result = ApplicationResult.rejected("FORBIDDEN", "actor cannot create slots");
      } else if (!administration.reliable()) {
        result = ApplicationResult.rejected("ADMINISTRATION_PROJECTION_UNRELIABLE",
            "wash administration projection is not reliable");
      } else if (slots.findById(command.slotId()).isPresent()) {
        result = ApplicationResult.rejected("SLOT_ALREADY_EXISTS", "slot already exists");
      } else {
        result = validateAdministrativeSource(command);
        if (result == null) {
          AppointmentTimeSlot slot = AppointmentTimeSlot.create(command.slotId(), command.sourceScheduleId(),
              command.serviceDate(), command.startsAt(), command.endsAt(), command.timezone(),
              command.bookingDeadlineAt(), command.cancellationDeadlineAt(), command.arrivalToleranceUntil());
          slots.save(slot);
          ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentTimeSlotCreated",
              slot.appointmentTimeSlotId(), slot.version(),
              "{\"slotId\":\"" + slot.appointmentTimeSlotId() + "\"}", now);
          String recalcPayload = "{\"sourceEventId\":\"" + command.metadata().messageId()
              + "\",\"slotId\":\"" + slot.appointmentTimeSlotId()
              + "\",\"expectedProjectionVersion\":0}";
          ApplicationSupport.append(outbox, new OutboxIntent(
              "RecalculateAppointmentCapacityForTimeSlot", "COMMAND", slot.appointmentTimeSlotId(),
              0L, command.metadata().correlationId(), command.metadata().messageId(), recalcPayload, now));
          result = ApplicationResult.applied(slot.appointmentTimeSlotId(), slot.version(),
              "appointment time slot created");
        }
      }
    } catch (IllegalArgumentException exception) {
      result = ApplicationResult.rejected("INVALID_SLOT", exception.getMessage());
    }
    ApplicationSupport.appendResult(outbox, command.metadata(), result, now);
    ApplicationSupport.complete(idempotency, command.metadata(), "CreateAppointmentTimeSlot", result, now);
    return result;
  }

  private ApplicationResult validateAdministrativeSource(Command command) {
    var schedule = administration.findSchedule(command.sourceScheduleId()).orElse(null);
    if (schedule == null || !"ACTIVE".equals(schedule.status())) {
      return ApplicationResult.rejected("SCHEDULE_NOT_ACTIVE", "source schedule is not active");
    }
    var calendar = administration.findCalendar(schedule.calendarId()).orElse(null);
    if (calendar == null || !"ACTIVE".equals(calendar.status())) {
      return ApplicationResult.rejected("CALENDAR_NOT_ACTIVE", "source calendar is not active");
    }
    if (!calendar.timezone().equals(command.timezone().getId())) {
      return ApplicationResult.rejected("TIMEZONE_MISMATCH", "slot timezone differs from source calendar");
    }
    if (command.serviceDate().isBefore(calendar.effectiveFrom())
        || (calendar.effectiveUntil() != null && command.serviceDate().isAfter(calendar.effectiveUntil()))) {
      return ApplicationResult.rejected("CALENDAR_OUT_OF_RANGE", "service date is outside calendar validity");
    }
    if (command.serviceDate().getDayOfWeek().getValue() != schedule.dayOfWeek()) {
      return ApplicationResult.rejected("SCHEDULE_DAY_MISMATCH", "service date does not match source schedule day");
    }
    var startLocal = command.startsAt().atZone(command.timezone()).toLocalTime();
    var endLocal = command.endsAt().atZone(command.timezone()).toLocalTime();
    if (startLocal.isBefore(schedule.openingTime()) || endLocal.isAfter(schedule.closingTime())) {
      return ApplicationResult.rejected("SLOT_OUTSIDE_SCHEDULE", "slot is outside source schedule hours");
    }
    if (Duration.between(command.startsAt(), command.endsAt()).toMinutes() != schedule.slotDurationMinutes()) {
      return ApplicationResult.rejected("SLOT_DURATION_MISMATCH", "slot duration differs from source schedule");
    }
    boolean serviceClosed = administration.findClosuresOverlapping(calendar.calendarId(),
        command.startsAt(), command.endsAt()).stream().anyMatch(c -> "SERVICE".equals(c.scope()));
    if (serviceClosed) {
      return ApplicationResult.rejected("ADMINISTRATIVE_CLOSURE", "service is administratively closed for this slot");
    }
    return null;
  }
}
