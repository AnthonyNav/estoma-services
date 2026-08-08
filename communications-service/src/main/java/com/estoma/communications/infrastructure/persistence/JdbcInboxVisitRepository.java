package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.InboxVisitRepository;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Same "don't let a delayed message overwrite a fresher one" idiom as {@code JdbcRecipientProjectionRepository}. */
@Repository
class JdbcInboxVisitRepository implements InboxVisitRepository {

  private final JdbcClient jdbc;

  JdbcInboxVisitRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public boolean upsertIfNewer(UUID accountId, String systemCode, Instant visitedAt, Instant now) {
    int rowsAffected =
        jdbc.sql(
                """
                INSERT INTO inbox_visits (account_id, system_code, last_visited_at, updated_at)
                VALUES (:accountId, :systemCode, :visitedAt, :updatedAt)
                ON CONFLICT (account_id, system_code) DO UPDATE SET
                  last_visited_at = EXCLUDED.last_visited_at,
                  updated_at = EXCLUDED.updated_at
                WHERE inbox_visits.last_visited_at < EXCLUDED.last_visited_at
                """)
            .param("accountId", accountId)
            .param("systemCode", systemCode)
            .param("visitedAt", Timestamps.toTimestamp(visitedAt))
            .param("updatedAt", Timestamps.toTimestamp(now))
            .update();
    return rowsAffected == 1;
  }
}
