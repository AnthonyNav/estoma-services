package com.estoma.appointments.application.port.out;

import java.util.UUID;

/** Issues the owner-defined representation used to render and index an appointment QR. */
public interface AppointmentQrRepresentationIssuer {
  boolean enabled();
  String issue(UUID appointmentId);
}
