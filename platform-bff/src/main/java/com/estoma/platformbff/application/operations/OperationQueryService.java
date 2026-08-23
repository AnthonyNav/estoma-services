package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.application.port.ClientOperationRepository;
import com.estoma.platformbff.domain.operation.ClientOperation;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class OperationQueryService {
  private final ClientOperationRepository operations;

  public OperationQueryService(ClientOperationRepository operations) {
    this.operations = operations;
  }

  public Optional<ClientOperation> findVisible(UUID operationId, UUID actorAccountId) {
    return operations.findById(operationId)
        .filter(operation -> operation.actorAccountId() == null
            ? actorAccountId == null
            : operation.actorAccountId().equals(actorAccountId));
  }
}
