package com.estoma.appointments.application.port.in;

import com.estoma.appointments.application.model.ApplicationResult;
import com.estoma.appointments.application.model.CommandMetadata;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public interface GrantExceptionalAppointmentAuthorizationUseCase {
  ApplicationResult grant(Command command);

  record Command(CommandMetadata metadata, UUID authorizationId, UUID studentAccountId,
      LocalDate serviceDate, String reason, Instant expiresAt, Instant now) {}
}
