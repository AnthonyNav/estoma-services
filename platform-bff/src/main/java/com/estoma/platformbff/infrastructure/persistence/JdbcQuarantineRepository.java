package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.QuarantineRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcQuarantineRepository implements QuarantineRepository {
  private final JdbcClient jdbc;

  public JdbcQuarantineRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(String consumerName, UUID messageId, String reasonCode, String bodySha256,
      Instant receivedAt) {
    jdbc.sql("""
        INSERT INTO quarantined_messages (consumer_name, message_id, reason_code, body_sha256, received_at)
        VALUES (:consumerName, :messageId, :reasonCode, :bodySha256, :receivedAt)
        ON CONFLICT (consumer_name, body_sha256) DO NOTHING
        """)
        .param("consumerName", consumerName)
        .param("messageId", messageId)
        .param("reasonCode", reasonCode)
        .param("bodySha256", bodySha256)
        .param("receivedAt", receivedAt)
        .update();
  }
}
