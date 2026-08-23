package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.security.RefreshSingleFlight;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class JdbcRefreshSingleFlight implements RefreshSingleFlight {
  private final JdbcClient jdbc;

  public JdbcRefreshSingleFlight(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public <T> T execute(UUID sessionId, Supplier<T> operation) {
    if (sessionId == null || operation == null) {
      throw new IllegalArgumentException("refresh single-flight requires sessionId and operation");
    }
    long lockKey = sessionId.getMostSignificantBits()
        ^ Long.rotateLeft(sessionId.getLeastSignificantBits(), 1);
    jdbc.sql("SELECT pg_advisory_xact_lock(:lockKey)")
        .param("lockKey", lockKey)
        .query((rs, rowNum) -> Boolean.TRUE)
        .single();
    return operation.get();
  }
}
