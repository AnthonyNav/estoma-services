package com.estoma.washexecution.application.port;

import com.estoma.washexecution.domain.execution.WashExecution;
import java.util.Optional;
import java.util.UUID;

public interface WashExecutionRepository {
  Optional<WashExecution> findById(UUID id);
  Optional<WashExecution> findByAppointmentId(UUID appointmentId);
  void save(WashExecution execution, int expectedVersion);
}
