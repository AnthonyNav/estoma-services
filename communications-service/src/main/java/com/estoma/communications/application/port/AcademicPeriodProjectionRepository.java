package com.estoma.communications.application.port;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

/** Local, rebuildable read model of Academic's current period. */
public interface AcademicPeriodProjectionRepository {

  record ActiveAcademicPeriod(UUID academicPeriodId, String code, LocalDate startDate, LocalDate endDate, int sourceVersion) {}

  void applyActive(ActiveAcademicPeriod period, Instant sourceOccurredAt, Instant now);

  /** Stores an owner recovery snapshot without changing the currently fenced recovery state. */
  void recordRecoveryActive(ActiveAcademicPeriod period, Instant sourceOccurredAt, Instant now);

  void applyClosed(UUID academicPeriodId, int sourceVersion, Instant sourceOccurredAt, Instant now);

  Optional<ActiveAcademicPeriod> findAuthoritativeActive();

  String readiness();

  void startRecovery(UUID queryMessageId, Instant now);

  boolean retryRecoveryIfOverdue(UUID queryMessageId, Instant now, Instant overdueBefore);

  boolean completeRecovery(UUID queryMessageId, Optional<ActiveAcademicPeriod> period, Instant sourceOccurredAt, Instant now);
}
