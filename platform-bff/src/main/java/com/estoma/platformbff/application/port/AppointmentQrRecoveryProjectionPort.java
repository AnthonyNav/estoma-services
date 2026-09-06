package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AppointmentQrRecoveryProjectionPort {
  enum ApplyOutcome { APPLIED, CONFLICT }

  record Snapshot(UUID appointmentId, long appointmentVersion, String qrRepresentation) {}

  ApplyOutcome applyPage(List<Snapshot> snapshots, Instant updatedAt);
}
