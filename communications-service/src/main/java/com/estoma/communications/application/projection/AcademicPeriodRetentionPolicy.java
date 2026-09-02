package com.estoma.communications.application.projection;

import com.estoma.communications.application.port.AcademicPeriodProjectionRepository;
import java.time.Instant;
import java.time.ZoneId;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Resolves only the explicit Wash semester-retention policy from local owner facts. */
@Component
public class AcademicPeriodRetentionPolicy {

  private final AcademicPeriodProjectionRepository repository;
  private final ZoneId institutionalTimeZone;

  AcademicPeriodRetentionPolicy(
      AcademicPeriodProjectionRepository repository,
      @Value("${estoma.communications.wash.institutional-time-zone:America/Mexico_City}") String institutionalTimeZone) {
    this.repository = repository;
    this.institutionalTimeZone = ZoneId.of(institutionalTimeZone);
  }

  /** The final academic date remains visible; expiry starts at the next local midnight. */
  public Instant resolveWashInboxExpiresAt() {
    var period = repository.findAuthoritativeActive()
        .orElseThrow(() -> new AcademicPeriodProjectionUnavailableException(repository.readiness()));
    Instant expiresAt = period.endDate().plusDays(1).atStartOfDay(institutionalTimeZone).toInstant();
    if (!expiresAt.isAfter(Instant.now())) {
      throw new AcademicPeriodProjectionUnavailableException(repository.readiness());
    }
    return expiresAt;
  }
}
