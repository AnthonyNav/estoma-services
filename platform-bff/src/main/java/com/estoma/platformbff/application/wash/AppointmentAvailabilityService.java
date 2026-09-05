package com.estoma.platformbff.application.wash;

import com.estoma.platformbff.application.port.AppointmentAvailabilityPort;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Context;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Refresh;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentAvailabilityResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentType;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AvailabilityBlockingReason;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AvailableAppointmentTimeSlot;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.PieceType;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Serves only fresh Appointments-owned snapshots and asynchronously refreshes cache misses. */
@Service
public class AppointmentAvailabilityService {
  private static final ZoneId SERVICE_ZONE = ZoneId.of("America/Mexico_City");

  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final AppointmentAvailabilityPort availability;
  private final Clock clock;
  private final Duration snapshotTtl;
  private final Duration refreshRetryAfter;
  private final long clientRetryAfterSeconds;

  public AppointmentAvailabilityService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, AppointmentAvailabilityPort availability, Clock clock,
      @Value("${platform.appointments.availability.snapshot-ttl:30s}") Duration snapshotTtl,
      @Value("${platform.appointments.availability.refresh-retry-after:30s}")
      Duration refreshRetryAfter,
      @Value("${platform.appointments.availability.client-retry-after-seconds:1}")
      long clientRetryAfterSeconds) {
    if (snapshotTtl == null || snapshotTtl.isNegative() || snapshotTtl.isZero()
        || snapshotTtl.compareTo(Duration.ofSeconds(30)) > 0) {
      throw new IllegalArgumentException("availability snapshot TTL must be within (0, 30s]");
    }
    if (refreshRetryAfter == null || refreshRetryAfter.isNegative() || refreshRetryAfter.isZero()) {
      throw new IllegalArgumentException("availability refresh retry interval must be positive");
    }
    if (clientRetryAfterSeconds < 1 || clientRetryAfterSeconds > 60) {
      throw new IllegalArgumentException("availability client retry interval must be within [1, 60]");
    }
    this.authorization = authorization;
    this.readiness = readiness;
    this.availability = availability;
    this.clock = clock;
    this.snapshotTtl = snapshotTtl;
    this.refreshRetryAfter = refreshRetryAfter;
    this.clientRetryAfterSeconds = clientRetryAfterSeconds;
  }

  public AppointmentAvailabilityResponse availability(AppointmentType appointmentType,
      int instrumentCount, PieceType pieceType, UUID courseSectionId) {
    UUID accountId = authorization.requireStudent();
    readiness.requireReady("appointments");
    validateInstrumentCount(appointmentType, instrumentCount);
    LocalDate serviceDate = clock.instant().atZone(SERVICE_ZONE).toLocalDate();
    Context context = new Context(accountId, serviceDate, appointmentType.name(), instrumentCount,
        pieceType.name(), courseSectionId);
    var snapshot = availability.findSnapshot(context).orElse(null);
    var now = clock.instant();
    if (snapshot == null || now.isBefore(snapshot.snapshotGeneratedAt())
        || now.isAfter(snapshot.freshUntil())
        || now.isAfter(snapshot.snapshotGeneratedAt().plus(snapshotTtl))) {
      var currentRefresh = availability.findRefresh(context).orElse(null);
      if (currentRefresh != null && "REJECTED".equals(currentRefresh.status())
          && currentRefresh.requestedAt().isAfter(now.minus(refreshRetryAfter))) {
        throw new IllegalArgumentException("appointment availability context was rejected by owner");
      }
      availability.enqueueRefresh(Refresh.pending(context, now), now.minus(refreshRetryAfter));
      throw new AppointmentAvailabilityRefreshingException(clientRetryAfterSeconds);
    }

    var slots = snapshot.availableTimeSlots().stream()
        .filter(slot -> !now.isAfter(slot.bookingDeadlineAt()))
        .map(slot -> new AvailableAppointmentTimeSlot(slot.appointmentTimeSlotId(), slot.startsAt(),
            slot.endsAt(), slot.availableCapacity(), slot.bookingDeadlineAt()))
        .toList();
    return new AppointmentAvailabilityResponse(snapshot.canSchedule(),
        snapshot.blockingReasons().stream().map(AvailabilityBlockingReason::valueOf).toList(),
        snapshot.dailyPenaltyPoints(), snapshot.dailyCompletedAppointments(),
        snapshot.exceptionalAuthorizationRequired(), snapshot.exceptionalAuthorizationAvailable(),
        snapshot.exceptionalAuthorizationId(), slots);
  }

  private static void validateInstrumentCount(AppointmentType type, int count) {
    int maximum = type == AppointmentType.JOURNEY ? 80 : 40;
    if (count < 1 || count > maximum) {
      throw new IllegalArgumentException("instrumentCount is outside the appointmentType range");
    }
  }

  public static final class AppointmentAvailabilityRefreshingException
      extends ProjectionUnavailableException {
    private final long retryAfterSeconds;

    public AppointmentAvailabilityRefreshingException(long retryAfterSeconds) {
      super("appointment availability is refreshing; retry after the owner snapshot arrives");
      this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
      return retryAfterSeconds;
    }
  }
}
