package com.estoma.platformbff.application.port;

import com.estoma.platformbff.domain.operation.ClientOperation;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface ClientOperationRepository {
  void insert(ClientOperation operation);

  Optional<ClientOperation> findById(UUID operationId);

  boolean completeIfPending(UUID operationId, ClientOperation terminalOperation, Instant now);
}
