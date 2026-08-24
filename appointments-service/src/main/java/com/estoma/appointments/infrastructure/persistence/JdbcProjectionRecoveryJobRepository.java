package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.ProjectionRecoveryJobRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionRecoveryJobRepository implements ProjectionRecoveryJobRepository {
  private final JdbcClient jdbc;
  public JdbcProjectionRecoveryJobRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public Optional<RecoveryJob> find(String projectionName) {
    return jdbc.sql("SELECT projection_name,generation_id,phase,current_query_message_id,cursor,context_json::text AS context_json,started_at,updated_at FROM projection_recovery_jobs WHERE projection_name=:name")
      .param("name",projectionName).query((rs,row)->new RecoveryJob(
          rs.getString("projection_name"),rs.getObject("generation_id",UUID.class),rs.getString("phase"),
          rs.getObject("current_query_message_id",UUID.class),rs.getString("cursor"),rs.getString("context_json"),
          Timestamps.toInstant(rs,"started_at"),Timestamps.toInstant(rs,"updated_at"))).optional();
  }

  @Override public boolean tryStart(RecoveryJob job) {
    return jdbc.sql("""
      INSERT INTO projection_recovery_jobs
        (projection_name,generation_id,phase,current_query_message_id,cursor,context_json,started_at,updated_at)
      VALUES (:name,:generation,:phase,:query,:cursor,CAST(:context AS jsonb),:started,:updated)
      ON CONFLICT (projection_name) DO NOTHING
      """)
      .param("name",job.projectionName()).param("generation",job.generationId()).param("phase",job.phase())
      .param("query",job.currentQueryMessageId()).param("cursor",job.cursor()).param("context",job.contextJson())
      .param("started",Timestamps.toTimestamp(job.startedAt())).param("updated",Timestamps.toTimestamp(job.updatedAt()))
      .update()==1;
  }

  @Override public boolean advance(String projectionName, UUID generationId, UUID expectedCurrentQueryMessageId,
      String phase, UUID nextQueryMessageId, String cursor, String contextJson, Instant updatedAt) {
    return jdbc.sql("UPDATE projection_recovery_jobs SET phase=:phase,current_query_message_id=:next,cursor=:cursor,context_json=CAST(:context AS jsonb),updated_at=:updated WHERE projection_name=:name AND generation_id=:generation AND current_query_message_id=:expected")
      .param("phase",phase).param("next",nextQueryMessageId).param("cursor",cursor).param("context",contextJson)
      .param("updated",Timestamps.toTimestamp(updatedAt)).param("name",projectionName).param("generation",generationId)
      .param("expected",expectedCurrentQueryMessageId).update()==1;
  }

  @Override public boolean complete(String projectionName, UUID generationId, UUID expectedCurrentQueryMessageId) {
    return jdbc.sql("DELETE FROM projection_recovery_jobs WHERE projection_name=:name AND generation_id=:generation AND current_query_message_id=:expected")
      .param("name",projectionName).param("generation",generationId).param("expected",expectedCurrentQueryMessageId).update()==1;
  }

  @Override public void delete(String projectionName) {
    jdbc.sql("DELETE FROM projection_recovery_jobs WHERE projection_name=:name").param("name",projectionName).update();
  }
}
