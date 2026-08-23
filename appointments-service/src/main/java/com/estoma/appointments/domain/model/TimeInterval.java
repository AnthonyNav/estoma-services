package com.estoma.appointments.domain.model;

import com.estoma.appointments.domain.error.DomainViolationException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Half-open interval [startsAt, endsAt). */
public record TimeInterval(Instant startsAt, Instant endsAt) {
  public TimeInterval {
    Objects.requireNonNull(startsAt, "startsAt");
    Objects.requireNonNull(endsAt, "endsAt");
    if (!endsAt.isAfter(startsAt)) throw new DomainViolationException("endsAt must be after startsAt");
  }

  public boolean overlaps(TimeInterval other) {
    Objects.requireNonNull(other, "other");
    return startsAt.isBefore(other.endsAt) && other.startsAt.isBefore(endsAt);
  }

  public TimeInterval plus(Duration duration) {
    Objects.requireNonNull(duration, "duration");
    return new TimeInterval(startsAt, endsAt.plus(duration));
  }
}
