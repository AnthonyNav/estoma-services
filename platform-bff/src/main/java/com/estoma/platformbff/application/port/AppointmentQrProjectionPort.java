package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.util.UUID;

/** Stores owner-issued appointment QR references without interpreting their opaque format. */
public interface AppointmentQrProjectionPort {
  void project(UUID appointmentId, String qrRepresentation, long appointmentVersion, Instant updatedAt);

  void invalidate(UUID appointmentId, long appointmentVersion, Instant updatedAt);
}
