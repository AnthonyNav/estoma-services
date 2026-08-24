package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.MissedEvaluationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMissedEvaluationRepository implements MissedEvaluationRepository {
  private static final String COLUMNS = "appointment_id,expected_appointment_version,current_query_message_id,status,snapshot_at,arrived_at,source_result_message_id,updated_at";
  private final JdbcClient jdbc;

  public JdbcMissedEvaluationRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public RequestDecision ensureQuery(UUID appointmentId, long expectedVersion,
      UUID proposedQueryMessageId, Instant now) {
    int inserted = jdbc.sql("""
        INSERT INTO missed_evaluation_confirmations
          (appointment_id,expected_appointment_version,current_query_message_id,status,updated_at)
        VALUES (:appointment,:version,:query,'QUERY_PENDING',:now)
        ON CONFLICT (appointment_id) DO UPDATE SET
          expected_appointment_version=EXCLUDED.expected_appointment_version,
          current_query_message_id=EXCLUDED.current_query_message_id,
          status='QUERY_PENDING',snapshot_at=NULL,arrived_at=NULL,source_result_message_id=NULL,
          updated_at=EXCLUDED.updated_at
        WHERE missed_evaluation_confirmations.expected_appointment_version <> EXCLUDED.expected_appointment_version
        """)
        .param("appointment", appointmentId)
        .param("version", expectedVersion)
        .param("query", proposedQueryMessageId)
        .param("now", Timestamps.toTimestamp(now))
        .update();
    Evaluation value = find(appointmentId, expectedVersion)
        .orElseThrow(() -> new IllegalStateException("missed evaluation row disappeared"));
    return new RequestDecision(value,
        inserted == 1 && proposedQueryMessageId.equals(value.currentQueryMessageId()));
  }

  @Override
  public Optional<Evaluation> find(UUID appointmentId, long expectedVersion) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM missed_evaluation_confirmations WHERE appointment_id=:appointment AND expected_appointment_version=:version")
        .param("appointment", appointmentId)
        .param("version", expectedVersion)
        .query(this::map)
        .optional();
  }

  @Override
  public Optional<Evaluation> findByQueryMessageId(UUID queryMessageId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM missed_evaluation_confirmations WHERE current_query_message_id=:query")
        .param("query", queryMessageId)
        .query(this::map)
        .optional();
  }

  @Override
  public boolean confirm(UUID appointmentId, long expectedVersion, UUID queryMessageId,
      UUID sourceResultMessageId, Instant snapshotAt, Instant arrivedAt, Instant updatedAt) {
    return jdbc.sql("""
        UPDATE missed_evaluation_confirmations
        SET status='CONFIRMED',snapshot_at=:snapshot,arrived_at=:arrived,
            source_result_message_id=:result,updated_at=:updated
        WHERE appointment_id=:appointment
          AND expected_appointment_version=:version
          AND current_query_message_id=:query
          AND status='QUERY_PENDING'
        """)
        .param("snapshot", Timestamps.toTimestamp(snapshotAt))
        .param("arrived", Timestamps.toTimestamp(arrivedAt))
        .param("result", sourceResultMessageId)
        .param("updated", Timestamps.toTimestamp(updatedAt))
        .param("appointment", appointmentId)
        .param("version", expectedVersion)
        .param("query", queryMessageId)
        .update() == 1;
  }

  private Evaluation map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new Evaluation(
        rs.getObject("appointment_id", UUID.class),
        rs.getLong("expected_appointment_version"),
        rs.getObject("current_query_message_id", UUID.class),
        rs.getString("status"),
        Timestamps.toInstant(rs, "snapshot_at"),
        Timestamps.toInstant(rs, "arrived_at"),
        rs.getObject("source_result_message_id", UUID.class),
        Timestamps.toInstant(rs, "updated_at"));
  }
}
