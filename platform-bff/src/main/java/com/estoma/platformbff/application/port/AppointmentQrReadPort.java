package com.estoma.platformbff.application.port;

import java.util.Optional;
import java.util.UUID;

/** Local read access to opaque owner-issued Appointment QR projections. */
public interface AppointmentQrReadPort {
  Optional<Mapping> findByAppointmentId(UUID appointmentId);

  Optional<Mapping> findByExactRepresentation(String qrRepresentation);

  record Mapping(UUID appointmentId, long appointmentVersion, String qrRepresentation) {}
}
