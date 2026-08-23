package com.estoma.washexecution.application.port;

import com.estoma.washexecution.domain.execution.EntryValidation;
import java.util.Optional;
import java.util.UUID;

public interface EntryValidationRepository {
  Optional<EntryValidation> findByExecutionId(UUID executionId);
  void save(EntryValidation validation);
}
