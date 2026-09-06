package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.projection.AppointmentQrRecoveryService;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "platform.recovery.appointment-qr", name = "enabled",
    havingValue = "true")
public class AppointmentQrRecoveryScheduler {
  private static final ZoneId SERVICE_ZONE = ZoneId.of("America/Mexico_City");
  private final AppointmentQrRecoveryService recovery;
  private final Clock clock;
  private final long restartRevision;

  public AppointmentQrRecoveryScheduler(AppointmentQrRecoveryService recovery, Clock clock,
      @Value("${platform.recovery.appointment-qr.restart-revision:0}") long restartRevision) {
    this.recovery = recovery;
    this.clock = clock;
    this.restartRevision = restartRevision;
  }

  @Scheduled(fixedDelayString = "${platform.recovery.appointment-qr.sweep-interval:30s}")
  public void maintain() {
    recovery.maintain(LocalDate.now(clock.withZone(SERVICE_ZONE)), clock.instant(), restartRevision);
  }
}
