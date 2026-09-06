package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.WashCompletionProjectionPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcWashCompletionProjectionRepository implements WashCompletionProjectionPort {
  private final JdbcClient jdbc;

  public JdbcWashCompletionProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  @Transactional
  public void projectCompletion(UUID washExecutionId, int finalPackageCount,
      int finalGreenPaperCassette8Count, int finalGreenPaperCassette10Count,
      int finalWitnessTapePortionCount, long executionVersion, Instant completedAt) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(CAST(:executionId AS TEXT), 0))")
        .param("executionId", washExecutionId)
        .query()
        .singleRow();
    int accepted = jdbc.sql("""
        UPDATE wash_execution_read_model
        SET final_package_count = :packages,
            final_green_paper_cassette_8_count = :cassette8,
            final_green_paper_cassette_10_count = :cassette10,
            final_witness_tape_portion_count = :witnessTape,
            final_exit_version = :version,
            updated_at = GREATEST(updated_at, :updatedAt)
        WHERE wash_execution_id = :executionId
          AND execution_version = :version
          AND final_exit_version < :version
        """)
        .param("packages", finalPackageCount)
        .param("cassette8", finalGreenPaperCassette8Count)
        .param("cassette10", finalGreenPaperCassette10Count)
        .param("witnessTape", finalWitnessTapePortionCount)
        .param("version", executionVersion)
        .param("updatedAt", JdbcTimes.timestamp(completedAt))
        .param("executionId", washExecutionId)
        .update();
    if (accepted == 0) return;

    jdbc.sql("""
        UPDATE wash_resource_assignment_read_model
        SET released_at = :completedAt,
            source_version = :version,
            updated_at = GREATEST(updated_at, :completedAt)
        WHERE wash_execution_id = :executionId
          AND released_at IS NULL
          AND source_version <= :version
        """)
        .param("completedAt", JdbcTimes.timestamp(completedAt))
        .param("version", executionVersion)
        .param("executionId", washExecutionId)
        .update();
  }
}
