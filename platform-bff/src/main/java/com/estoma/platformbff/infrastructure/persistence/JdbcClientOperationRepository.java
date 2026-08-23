package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.ClientOperationRepository;
import com.estoma.platformbff.domain.operation.ClientOperation;
import com.estoma.platformbff.domain.operation.OperationStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcClientOperationRepository implements ClientOperationRepository {
  private final JdbcClient jdbc;

  public JdbcClientOperationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(ClientOperation operation) {
    jdbc.sql("""
        INSERT INTO client_operations (
          operation_id, actor_account_id, public_operation, command_type,
          owner_logical_component_id, command_routing_key, correlation_id,
          idempotency_key, status, result_data, error_code, error_details,
          created_at, updated_at, completed_at, expires_at
        ) VALUES (
          :operationId, :actorAccountId, :publicOperation, :commandType,
          :ownerLogicalComponentId, :commandRoutingKey, :correlationId,
          :idempotencyKey, :status, CAST(:resultData AS jsonb), :errorCode,
          CAST(:errorDetails AS jsonb), :createdAt, :updatedAt, :completedAt, :expiresAt
        )
        """)
        .param("operationId", operation.operationId())
        .param("actorAccountId", operation.actorAccountId())
        .param("publicOperation", operation.publicOperation())
        .param("commandType", operation.commandType())
        .param("ownerLogicalComponentId", operation.ownerLogicalComponentId())
        .param("commandRoutingKey", operation.commandRoutingKey())
        .param("correlationId", operation.correlationId())
        .param("idempotencyKey", operation.idempotencyKey())
        .param("status", operation.status().name())
        .param("resultData", operation.resultData())
        .param("errorCode", operation.errorCode())
        .param("errorDetails", operation.errorDetails())
        .param("createdAt", operation.createdAt())
        .param("updatedAt", operation.updatedAt())
        .param("completedAt", operation.completedAt())
        .param("expiresAt", operation.expiresAt())
        .update();
  }

  @Override
  public Optional<ClientOperation> findById(UUID operationId) {
    return jdbc.sql("SELECT * FROM client_operations WHERE operation_id = :operationId")
        .param("operationId", operationId)
        .query((rs, rowNum) -> map(rs))
        .optional();
  }

  @Override
  public boolean completeIfPending(UUID operationId, ClientOperation terminalOperation, Instant now) {
    if (terminalOperation == null || !terminalOperation.status().terminal()) {
      throw new IllegalArgumentException("client operation completion requires a terminal status");
    }
    if (!operationId.equals(terminalOperation.operationId())) {
      throw new IllegalArgumentException("operation id does not match terminal operation");
    }

    return jdbc.sql("""
        UPDATE client_operations
        SET status = :status,
            result_data = CAST(:resultData AS jsonb),
            error_code = :errorCode,
            error_details = CAST(:errorDetails AS jsonb),
            updated_at = :updatedAt,
            completed_at = :completedAt
        WHERE operation_id = :operationId AND status = 'PENDING'
        """)
        .param("status", terminalOperation.status().name())
        .param("resultData", terminalOperation.resultData())
        .param("errorCode", terminalOperation.errorCode())
        .param("errorDetails", terminalOperation.errorDetails())
        .param("updatedAt", now)
        .param("completedAt", terminalOperation.completedAt() == null ? now : terminalOperation.completedAt())
        .param("operationId", operationId)
        .update() == 1;
  }

  private static ClientOperation map(ResultSet rs) throws SQLException {
    return new ClientOperation(
        rs.getObject("operation_id", UUID.class),
        rs.getObject("actor_account_id", UUID.class),
        rs.getString("public_operation"),
        rs.getString("command_type"),
        rs.getString("owner_logical_component_id"),
        rs.getString("command_routing_key"),
        rs.getObject("correlation_id", UUID.class),
        rs.getString("idempotency_key"),
        OperationStatus.valueOf(rs.getString("status")),
        rs.getString("result_data"),
        rs.getString("error_code"),
        rs.getString("error_details"),
        instant(rs, "created_at"),
        instant(rs, "updated_at"),
        instant(rs, "completed_at"),
        instant(rs, "expires_at"));
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    var timestamp = rs.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }
}
