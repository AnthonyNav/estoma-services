package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.WashExitMaterialsProjectionPort;
import java.time.Instant;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashExitMaterialsProjectionRepository implements WashExitMaterialsProjectionPort {
  private final JdbcClient jdbc;

  public JdbcWashExitMaterialsProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void projectSubmittedExitMaterials(UUID washExecutionId, int packageCount,
      int greenPaperCassette8Count, int greenPaperCassette10Count, int witnessTapePortionCount,
      Instant submittedAt, long executionVersion, Instant updatedAt) {
    jdbc.sql("""
        UPDATE wash_execution_read_model
        SET submitted_package_count = :packageCount,
            submitted_green_paper_cassette_8_count = :cassette8,
            submitted_green_paper_cassette_10_count = :cassette10,
            submitted_witness_tape_portion_count = :witnessTape,
            exit_submitted_at = COALESCE(exit_submitted_at, :submittedAt),
            submitted_exit_version = :version,
            updated_at = GREATEST(updated_at, :updatedAt)
        WHERE wash_execution_id = :executionId
          AND submitted_exit_version <= :version
        """)
        .param("executionId", washExecutionId)
        .param("packageCount", packageCount)
        .param("cassette8", greenPaperCassette8Count)
        .param("cassette10", greenPaperCassette10Count)
        .param("witnessTape", witnessTapePortionCount)
        .param("submittedAt", JdbcTimes.timestamp(submittedAt))
        .param("version", executionVersion)
        .param("updatedAt", JdbcTimes.timestamp(updatedAt))
        .update();
  }
}
