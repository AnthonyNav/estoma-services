package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AcademicProjectionRepository {
  void upsert(AcademicProjection projection);
  Optional<AcademicProjection> findStudentCourse(UUID studentAccountId, UUID courseSectionId);
  Optional<Instant> lastUpdated(UUID sourceId);

  record AcademicProjection(UUID sourceId, UUID studentAccountId, UUID courseSectionId,
      String status, long sourceVersion, Instant updatedAt, boolean reliable) {}
}
