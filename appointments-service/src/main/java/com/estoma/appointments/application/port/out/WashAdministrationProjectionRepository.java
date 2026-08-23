package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.UUID;

public interface WashAdministrationProjectionRepository {
  void upsert(AdministrationProjection projection);
  boolean reliable();
  record AdministrationProjection(UUID sourceId, String projectionType, String payloadJson,
      long sourceVersion, Instant updatedAt) {}
}
