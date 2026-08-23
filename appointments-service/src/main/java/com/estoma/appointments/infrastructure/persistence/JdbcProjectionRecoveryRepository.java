package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.ProjectionRecoveryRepository;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionRecoveryRepository implements ProjectionRecoveryRepository {
  private final JdbcClient jdbc;
  public JdbcProjectionRecoveryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public RecoveryState get(String name) {
    return jdbc.sql("SELECT projection_name,status,source_version,updated_at,last_error,version FROM projection_recovery_state WHERE projection_name=:name")
      .param("name",name).query((rs,row)->new RecoveryState(rs.getString("projection_name"),rs.getString("status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"),rs.getString("last_error"),rs.getLong("version"))).optional()
      .orElse(new RecoveryState(name, "UNINITIALIZED", 0, Instant.EPOCH, null, 1));
  }
  @Override public boolean update(RecoveryState s, long expectedVersion) {
    int count = jdbc.sql("UPDATE projection_recovery_state SET status=:status,source_version=:sourceVersion,updated_at=:updatedAt,last_error=:error,version=:version WHERE projection_name=:name AND version=:expectedVersion")
      .param("status",s.status()).param("sourceVersion",s.sourceVersion()).param("updatedAt",Timestamps.toTimestamp(s.updatedAt())).param("error",s.lastError()).param("version",s.version()).param("name",s.projectionName()).param("expectedVersion",expectedVersion).update();
    if (count == 1) return true;
    return jdbc.sql("INSERT INTO projection_recovery_state (projection_name,status,source_version,updated_at,last_error,version) VALUES (:name,:status,:sourceVersion,:updatedAt,:error,:version) ON CONFLICT DO NOTHING")
      .param("name",s.projectionName()).param("status",s.status()).param("sourceVersion",s.sourceVersion()).param("updatedAt",Timestamps.toTimestamp(s.updatedAt())).param("error",s.lastError()).param("version",s.version()).update() == 1;
  }
}
