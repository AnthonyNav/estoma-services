package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.CommunicationAdminActionRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcCommunicationAdminActionRepository implements CommunicationAdminActionRepository {
  private final JdbcClient jdbc;
  JdbcCommunicationAdminActionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override
  public void record(UUID actionId, UUID commandMessageId, UUID emailDeliveryId, String action, Instant createdAt) {
    record(actionId, commandMessageId, emailDeliveryId, action, "{\"type\":\"SYSTEM\",\"service\":\"SYSTEM\"}", action, "{}", null, false, createdAt);
  }

  @Override
  public void record(UUID actionId, UUID commandMessageId, UUID emailDeliveryId, String action, String actorJson,
      String reason, String context, Integer resultingRetryCycle, boolean duplicateRisk, Instant createdAt) {
    jdbc.sql("""
        INSERT INTO email_delivery_administrative_actions(action_id, command_message_id, email_delivery_id, action, actor, reason, context, resulting_retry_cycle, duplicate_risk, created_at)
        VALUES (:actionId, :commandMessageId, :emailDeliveryId, :action, CAST(:actorJson AS jsonb), :reason, CAST(:context AS jsonb), :resultingRetryCycle, :duplicateRisk, :createdAt)
        ON CONFLICT (command_message_id, email_delivery_id, action) DO NOTHING
        """)
        .param("actionId", actionId).param("commandMessageId", commandMessageId).param("emailDeliveryId", emailDeliveryId)
        .param("action", action).param("actorJson", actorJson).param("reason", reason).param("context", context)
        .param("resultingRetryCycle", resultingRetryCycle).param("duplicateRisk", duplicateRisk)
        .param("createdAt", Timestamps.toTimestamp(createdAt)).update();
  }
}
