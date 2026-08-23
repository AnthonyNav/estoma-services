package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.CommandIdempotencyRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcCommandIdempotencyRepository implements CommandIdempotencyRepository {
  private final JdbcClient jdbc;
  public JdbcCommandIdempotencyRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public IdempotencyDecision claim(String scope, String key, String fingerprint, UUID messageId, Instant now) {
    int inserted = jdbc.sql("INSERT INTO command_idempotency_records (scope,idempotency_key,request_fingerprint,message_id,status,created_at) VALUES (:scope,:key,:fingerprint,:messageId,'IN_PROGRESS',:now) ON CONFLICT (scope,idempotency_key) DO NOTHING")
      .param("scope", scope).param("key", key).param("fingerprint", fingerprint).param("messageId", messageId).param("now", Timestamps.toTimestamp(now)).update();
    IdempotencyRecord existing = find(scope, key).orElseThrow();
    if (inserted == 1) return new IdempotencyDecision(true, false, false, existing);
    boolean conflict = !existing.fingerprint().equals(fingerprint);
    return new IdempotencyDecision(false, !conflict, conflict, existing);
  }
  @Override public boolean complete(String scope, String key, String outcomeJson, Instant at) {
    return jdbc.sql("UPDATE command_idempotency_records SET status='COMPLETED',outcome_json=CAST(:outcome AS jsonb),completed_at=:at WHERE scope=:scope AND idempotency_key=:key AND status='IN_PROGRESS'")
      .param("outcome", outcomeJson).param("at", Timestamps.toTimestamp(at)).param("scope", scope).param("key", key).update() == 1;
  }
  @Override public Optional<IdempotencyRecord> find(String scope, String key) {
    return jdbc.sql("SELECT scope,idempotency_key,request_fingerprint,message_id,status,outcome_json::text AS outcome_json,created_at,completed_at FROM command_idempotency_records WHERE scope=:scope AND idempotency_key=:key")
      .param("scope", scope).param("key", key).query((rs, row) -> new IdempotencyRecord(rs.getString("scope"), rs.getString("idempotency_key"), rs.getString("request_fingerprint"), rs.getObject("message_id", UUID.class), rs.getString("status"), rs.getString("outcome_json"), Timestamps.toInstant(rs, "created_at"), Timestamps.toInstant(rs, "completed_at"))).optional();
  }
}
