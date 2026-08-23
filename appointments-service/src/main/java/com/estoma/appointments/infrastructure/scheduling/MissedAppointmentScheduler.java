package com.estoma.appointments.infrastructure.scheduling;

import com.estoma.appointments.application.port.out.AppointmentRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Emits durable, idempotent internal Commands after the functional arrival tolerance plus technical grace. */
@Component
public class MissedAppointmentScheduler {
  private final AppointmentRepository appointments;
  private final OutboxRepository outbox;
  private final ProjectionRecoveryCoordinator recovery;
  private final Clock clock;
  private final Duration technicalGrace;
  private final int batchSize;

  public MissedAppointmentScheduler(AppointmentRepository appointments, OutboxRepository outbox,
      ProjectionRecoveryCoordinator recovery, Clock clock,
      @Value("${estoma.appointments.missed-technical-grace:PT1M}") Duration technicalGrace,
      @Value("${estoma.appointments.missed-scan-batch-size:100}") int batchSize) {
    this.appointments = appointments;
    this.outbox = outbox;
    this.recovery = recovery;
    this.clock = clock;
    this.technicalGrace = technicalGrace == null || technicalGrace.isNegative()
        ? Duration.ofMinutes(1) : technicalGrace;
    this.batchSize = batchSize > 0 ? batchSize : 100;
  }

  @Scheduled(fixedDelayString = "${estoma.appointments.missed-scan-interval:30s}")
  @Transactional
  public void emitDueCommands() {
    if (!recovery.isReady("wash-execution")) return;
    Instant now = clock.instant();
    Instant cutoff = now.minus(technicalGrace);
    for (var appointment : appointments.findMissedCandidates(cutoff, batchSize)) {
      UUID messageId = deterministicMessageId(appointment.appointmentId(), appointment.version());
      String payload = "{\"appointmentId\":\"" + appointment.appointmentId()
          + "\",\"expectedVersion\":" + appointment.version() + "}";
      outbox.append(new OutboxRepository.OutboxMessage(messageId, "MarkAppointmentAsMissed", 1,
          "COMMAND", appointment.appointmentId(), appointment.version(), messageId, null, payload,
          now, now, "PENDING", 0, null, null));
    }
  }

  static UUID deterministicMessageId(UUID appointmentId, long version) {
    return UUID.nameUUIDFromBytes(("appointments:mark-missed:" + appointmentId + ":" + version)
        .getBytes(StandardCharsets.UTF_8));
  }
}
