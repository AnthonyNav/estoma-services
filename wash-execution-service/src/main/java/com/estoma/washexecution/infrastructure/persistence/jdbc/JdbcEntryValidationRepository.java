package com.estoma.washexecution.infrastructure.persistence.jdbc;

import com.estoma.washexecution.application.port.EntryValidationRepository;
import com.estoma.washexecution.domain.execution.EntryDecision;
import com.estoma.washexecution.domain.execution.EntryValidation;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcEntryValidationRepository implements EntryValidationRepository {
  private final JdbcTemplate jdbc;

  public JdbcEntryValidationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Override
  public Optional<EntryValidation> findByExecutionId(UUID id) {
    return jdbc.query("SELECT * FROM entry_validations WHERE wash_execution_id = ?", this::map, id).stream().findFirst();
  }

  @Override
  public void save(EntryValidation v) {
    jdbc.update("""
        INSERT INTO entry_validations (entry_validation_id, wash_execution_id, identity_confirmed, requirements_satisfied,
          decision, rejection_reason, decided_by_account_id, decided_at, created_at)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
        """, v.entryValidationId(), v.washExecutionId(), v.identityConfirmed(), v.requirementsSatisfied(), v.decision().name(),
        v.rejectionReason(), v.decidedByAccountId(), v.decidedAt(), Instant.now());
  }

  private EntryValidation map(ResultSet rs, int row) throws SQLException {
    return new EntryValidation(rs.getObject("entry_validation_id", UUID.class), rs.getObject("wash_execution_id", UUID.class),
        rs.getBoolean("identity_confirmed"), rs.getBoolean("requirements_satisfied"),
        EntryDecision.valueOf(rs.getString("decision")), rs.getString("rejection_reason"),
        rs.getObject("decided_by_account_id", UUID.class), rs.getTimestamp("decided_at").toInstant());
  }
}
