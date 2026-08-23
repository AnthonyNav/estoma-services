package com.estoma.platformbff.api.operations;

import com.estoma.platformbff.application.operations.OperationQueryService;
import com.estoma.platformbff.application.port.CurrentActor;
import com.estoma.platformbff.application.port.OperationDataMapper;
import com.estoma.platformbff.contracts.http.OperationResponse;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/operations")
public class OperationsController {
  private final OperationQueryService operations;
  private final CurrentActor currentActor;
  private final OperationDataMapper dataMapper;

  public OperationsController(OperationQueryService operations, CurrentActor currentActor,
      OperationDataMapper dataMapper) {
    this.operations = operations;
    this.currentActor = currentActor;
    this.dataMapper = dataMapper;
  }

  @GetMapping("/{operationId}")
  public ResponseEntity<OperationResponse> get(@PathVariable UUID operationId) {
    return operations.findVisible(operationId, currentActor.accountId().orElse(null))
        .map(operation -> ResponseEntity.ok(new OperationResponse(operation.operationId(),
            operation.status().name(), operation.publicOperation(), operation.errorCode(),
            operation.resultData() == null ? null : dataMapper.toHttpValue(operation.resultData()),
            operation.createdAt(), operation.updatedAt(), operation.completedAt())))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }
}
