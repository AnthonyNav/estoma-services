package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcRecipientProjectionRepository implements RecipientProjectionRepository {

  private final JdbcClient jdbc;

  JdbcRecipientProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<RecipientProjectionRecord> findByAccountId(UUID accountId) {
    return jdbc.sql(
            """
            SELECT account_id, person_id, display_name, institutional_email, person_status, account_status,
                   person_source_version, account_source_version
            FROM recipient_projection WHERE account_id = :accountId
            """)
        .param("accountId", accountId)
        .query(JdbcRecipientProjectionRepository::mapRow)
        .optional();
  }

  @Override
  public boolean upsertAccountIfNewer(UUID accountId, UUID personId, String accountStatus, int accountSourceVersion, Instant now) {
    int rowsAffected = jdbc.sql(
            """
            INSERT INTO recipient_projection (account_id, person_id, account_status, account_source_version, updated_at)
            VALUES (:accountId, :personId, CAST(:accountStatus AS projected_status), :accountSourceVersion, :updatedAt)
            ON CONFLICT (account_id) DO UPDATE SET
              person_id = EXCLUDED.person_id,
              account_status = EXCLUDED.account_status,
              account_source_version = EXCLUDED.account_source_version,
              updated_at = EXCLUDED.updated_at
            WHERE recipient_projection.account_source_version < EXCLUDED.account_source_version
            """)
        .param("accountId", accountId)
        .param("personId", personId)
        .param("accountStatus", accountStatus)
        .param("accountSourceVersion", accountSourceVersion)
        .param("updatedAt", Timestamps.toTimestamp(now))
        .update();
    return rowsAffected == 1;
  }

  @Override
  public int updatePersonIfNewer(UUID personId, String displayName, String institutionalEmail, String personStatus, int personSourceVersion, Instant now) {
    return jdbc.sql(
            """
            UPDATE recipient_projection SET
              display_name = :displayName,
              institutional_email = :institutionalEmail,
              person_status = CAST(:personStatus AS projected_status),
              person_source_version = :personSourceVersion,
              updated_at = :updatedAt
            WHERE person_id = :personId AND (person_source_version IS NULL OR person_source_version < :personSourceVersion)
            """)
        .param("displayName", displayName)
        .param("institutionalEmail", institutionalEmail)
        .param("personStatus", personStatus)
        .param("personSourceVersion", personSourceVersion)
        .param("updatedAt", Timestamps.toTimestamp(now))
        .param("personId", personId)
        .update();
  }

  @Override
  public int updatePersonDetailsIfNewer(UUID personId, String displayName, String institutionalEmail, int personSourceVersion, Instant now) {
    return jdbc.sql(
            """
            UPDATE recipient_projection SET
              display_name = :displayName,
              institutional_email = :institutionalEmail,
              person_source_version = :personSourceVersion,
              updated_at = :updatedAt
            WHERE person_id = :personId AND (person_source_version IS NULL OR person_source_version < :personSourceVersion)
            """)
        .param("displayName", displayName)
        .param("institutionalEmail", institutionalEmail)
        .param("personSourceVersion", personSourceVersion)
        .param("updatedAt", Timestamps.toTimestamp(now))
        .param("personId", personId)
        .update();
  }

  @Override
  public int updatePersonStatusIfNewer(UUID personId, String personStatus, int personSourceVersion, Instant now) {
    return jdbc.sql(
            """
            UPDATE recipient_projection SET
              person_status = CAST(:personStatus AS projected_status),
              person_source_version = :personSourceVersion,
              updated_at = :updatedAt
            WHERE person_id = :personId AND (person_source_version IS NULL OR person_source_version < :personSourceVersion)
            """)
        .param("personStatus", personStatus)
        .param("personSourceVersion", personSourceVersion)
        .param("updatedAt", Timestamps.toTimestamp(now))
        .param("personId", personId)
        .update();
  }

  private static RecipientProjectionRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    int personSourceVersion = rs.getInt("person_source_version");
    Integer nullablePersonSourceVersion = rs.wasNull() ? null : personSourceVersion;
    return new RecipientProjectionRecord(
        rs.getObject("account_id", UUID.class),
        rs.getObject("person_id", UUID.class),
        rs.getString("display_name"),
        rs.getString("institutional_email"),
        rs.getString("person_status"),
        rs.getString("account_status"),
        nullablePersonSourceVersion,
        rs.getInt("account_source_version"));
  }
}
