package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProcessedMessageRepository implements ProcessedMessageRepository {
  private final JdbcClient jdbc;
  public JdbcProcessedMessageRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public boolean tryClaim(UUID id, String type, Instant receivedAt) {
    return jdbc.sql("INSERT INTO processed_messages (message_id,message_type,status,received_at) VALUES (:id,:type,'CLAIMED',:receivedAt) ON CONFLICT (message_id) DO UPDATE SET status='CLAIMED',received_at=EXCLUDED.received_at,deferred_at=NULL,last_error=NULL WHERE processed_messages.status='DEFERRED'")
      .param("id", id).param("type", type).param("receivedAt", Timestamps.toTimestamp(receivedAt)).update() == 1;
  }
  @Override public boolean markApplied(UUID id, Instant at) {
    return jdbc.sql("UPDATE processed_messages SET status='APPLIED',applied_at=:at,last_error=NULL WHERE message_id=:id AND status IN ('CLAIMED','DEFERRED')")
      .param("id", id).param("at", Timestamps.toTimestamp(at)).update() == 1;
  }
  @Override public boolean markDeferred(UUID id, String reason, Instant at) {
    return jdbc.sql("UPDATE processed_messages SET status='DEFERRED',deferred_at=:at,last_error=:reason WHERE message_id=:id AND status='CLAIMED'")
      .param("id", id).param("at", Timestamps.toTimestamp(at)).param("reason", reason).update() == 1;
  }
  @Override public boolean wasProcessed(UUID id) {
    return jdbc.sql("SELECT count(*) FROM processed_messages WHERE message_id=:id AND status='APPLIED'").param("id", id).query(Long.class).single() > 0;
  }
}
