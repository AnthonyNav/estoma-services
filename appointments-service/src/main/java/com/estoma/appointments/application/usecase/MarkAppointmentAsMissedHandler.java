package com.estoma.appointments.application.usecase;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.OutboxIntent;
import com.estoma.appointments.application.port.in.MarkAppointmentAsMissedUseCase;
import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.AppointmentTimeSlotRepository;
import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import com.estoma.appointments.application.port.out.MissedEvaluationRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingWashExecutionEventRepository;
import com.estoma.appointments.application.port.out.ProjectionRecoveryRepository;
import com.estoma.appointments.application.port.out.WashExecutionEvidenceRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MarkAppointmentAsMissedHandler implements MarkAppointmentAsMissedUseCase {
  private final AppointmentRepository appointments;
  private final AppointmentTimeSlotRepository slots;
  private final WashExecutionEvidenceRepository evidence;
  private final PendingWashExecutionEventRepository pending;
  private final ProjectionRecoveryRepository recovery;
  private final MissedEvaluationRepository missedEvaluations;
  private final CommandIdempotencyRepository idempotency;
  private final OutboxRepository outbox;

  public MarkAppointmentAsMissedHandler(AppointmentRepository appointments,
      AppointmentTimeSlotRepository slots, WashExecutionEvidenceRepository evidence,
      PendingWashExecutionEventRepository pending, ProjectionRecoveryRepository recovery,
      MissedEvaluationRepository missedEvaluations, CommandIdempotencyRepository idempotency,
      OutboxRepository outbox) {
    this.appointments = appointments;
    this.slots = slots;
    this.evidence = evidence;
    this.pending = pending;
    this.recovery = recovery;
    this.missedEvaluations = missedEvaluations;
    this.idempotency = idempotency;
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public ApplicationResult mark(Command command) {
    Instant now = command.now();
    Optional<ApplicationResult> gate = ApplicationSupport.claim(idempotency, command.metadata(),
        "MarkAppointmentAsMissed", ApplicationSupport.fingerprint(command.appointmentId(), command.expectedVersion()), now);
    if (gate.isPresent()) return gate.get();

    var appointment = appointments.findByIdForUpdate(command.appointmentId()).orElse(null);
    ApplicationResult result;
    if (appointment == null) {
      result = ApplicationResult.rejected("APPOINTMENT_NOT_FOUND", "appointment was not found");
    } else if (appointment.version() != command.expectedVersion()) {
      result = ApplicationResult.rejected("VERSION_CONFLICT", "appointment version changed");
    } else if (!appointment.status().name().equals("SCHEDULED")) {
      result = ApplicationResult.rejected("NOT_MISSED_ELIGIBLE", "appointment is not scheduled");
    } else {
      var slot = slots.findByIdForUpdate(appointment.appointmentTimeSlotId()).orElse(null);
      if (slot == null || !now.isAfter(slot.arrivalToleranceUntil())) {
        result = ApplicationResult.rejected("ARRIVAL_TOLERANCE_ACTIVE", "arrival tolerance has not ended");
      } else if (appointment.entryAuthorizedAt() != null) {
        result = ApplicationResult.rejected("ENTRY_AUTHORIZED", "entry-authorized appointment cannot be missed");
      } else if (!executionProjectionReady() || pending.hasOpenForAppointment(appointment.appointmentId())) {
        result = ApplicationResult.deferred("EXECUTION_PROJECTION_UNRELIABLE",
            "execution evidence is not yet causally settled", appointment.appointmentId());
      } else if (evidence.hasValidArrival(appointment.appointmentId(), slot.arrivalToleranceUntil())) {
        result = ApplicationResult.rejected("VALID_ARRIVAL_PRESENT", "valid arrival evidence protects the appointment");
      } else {
        var confirmation = missedEvaluations.find(appointment.appointmentId(), appointment.version()).orElse(null);
        if (confirmation == null || !confirmation.confirmedAfter(slot.arrivalToleranceUntil())) {
          result = requestAuthoritativeConfirmation(command, appointment.appointmentId(), appointment.version(), now);
        } else if (validArrival(confirmation.arrivedAt(), slot.startsAt(), slot.arrivalToleranceUntil())) {
          result = ApplicationResult.rejected("VALID_ARRIVAL_PRESENT",
              "authoritative Execution snapshot confirms a valid arrival");
        } else {
          var missed = appointment.markMissed(now);
          if (!appointments.updateIfVersion(missed, command.expectedVersion())) {
            result = ApplicationResult.deferred("CONCURRENT_MODIFICATION",
                "appointment changed while marking missed", appointment.appointmentId());
          } else {
            ApplicationSupport.appendEvent(outbox, command.metadata(), "AppointmentMissed",
                missed.appointmentId(), missed.version(), AppointmentEventPayloads.missed(missed), now);
            result = ApplicationResult.applied(missed.appointmentId(), missed.version(), "appointment marked missed");
          }
        }
      }
    }

    if (result.status() == ApplicationResult.Status.DEFERRED) {
      ApplicationSupport.releaseDeferred(idempotency, command.metadata(), "MarkAppointmentAsMissed", result);
    } else {
      ApplicationSupport.complete(idempotency, command.metadata(), "MarkAppointmentAsMissed", result, now);
    }
    return result;
  }

  private ApplicationResult requestAuthoritativeConfirmation(Command command, UUID appointmentId,
      long appointmentVersion, Instant now) {
    UUID queryMessageId = UUID.randomUUID();
    var decision = missedEvaluations.ensureQuery(appointmentId, appointmentVersion, queryMessageId, now);
    if (decision.queryCreated()) {
      String payload = "{\"appointmentId\":\"" + appointmentId + "\"}";
      ApplicationSupport.append(outbox, queryMessageId, new OutboxIntent(
          "GetWashExecutionIntegrationContext", "QUERY", appointmentId, appointmentVersion,
          command.metadata().correlationId(), command.metadata().messageId(), payload, now,
          queryMessageId));
    }
    return ApplicationResult.deferred("AUTHORITATIVE_EXECUTION_CONFIRMATION_PENDING",
        "MISSED waits for authoritative Execution evidence", appointmentId);
  }

  private static boolean validArrival(Instant arrivedAt, Instant startsAt, Instant toleranceUntil) {
    return arrivedAt != null && !arrivedAt.isBefore(startsAt.minusSeconds(15 * 60L))
        && !arrivedAt.isAfter(toleranceUntil);
  }

  private boolean executionProjectionReady() {
    var state = recovery.get("wash-execution");
    return state != null && "READY".equals(state.status());
  }
}
