package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.StudentNumberSyncRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** JDBC implementation of {@link StudentNumberSyncRepository}. */
@Repository
class JdbcStudentNumberSyncRepository implements StudentNumberSyncRepository {

  private static final String COLUMNS =
      "account_id, student_academic_profile_id, last_applied_student_number_version, synchronization_status, last_error_code";

  private final JdbcClient jdbc;

  JdbcStudentNumberSyncRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<StudentNumberSyncRecord> findByAccountId(UUID accountId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM student_number_login_sync_projection WHERE account_id = :accountId")
        .param("accountId", accountId)
        .query(JdbcStudentNumberSyncRepository::mapRow)
        .optional();
  }

  @Override
  public void ensureExists(UUID accountId, UUID studentAcademicProfileId, Instant updatedAt) {
    jdbc.sql(
            """
            INSERT INTO student_number_login_sync_projection (account_id, student_academic_profile_id, updated_at)
            VALUES (:accountId, :studentAcademicProfileId, :updatedAt)
            ON CONFLICT (account_id) DO NOTHING
            """)
        .param("accountId", accountId)
        .param("studentAcademicProfileId", studentAcademicProfileId)
        .param("updatedAt", Timestamps.toTimestamp(updatedAt))
        .update();
  }

  @Override
  public int markSynchronized(UUID accountId, int newAppliedVersion, Instant updatedAt) {
    return jdbc.sql(
            """
            UPDATE student_number_login_sync_projection SET
              last_applied_student_number_version = :newAppliedVersion,
              synchronization_status = CAST('SYNCHRONIZED' AS student_number_sync_status),
              last_error_code = NULL,
              updated_at = :updatedAt
            WHERE account_id = :accountId
            """)
        .param("newAppliedVersion", newAppliedVersion)
        .param("updatedAt", Timestamps.toTimestamp(updatedAt))
        .param("accountId", accountId)
        .update();
  }

  @Override
  public int markFailed(UUID accountId, String errorCode, Instant updatedAt) {
    return jdbc.sql(
            """
            UPDATE student_number_login_sync_projection SET
              synchronization_status = CAST('FAILED' AS student_number_sync_status),
              last_error_code = :errorCode,
              updated_at = :updatedAt
            WHERE account_id = :accountId
            """)
        .param("errorCode", errorCode)
        .param("updatedAt", Timestamps.toTimestamp(updatedAt))
        .param("accountId", accountId)
        .update();
  }

  private static StudentNumberSyncRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new StudentNumberSyncRecord(
        rs.getObject("account_id", UUID.class),
        rs.getObject("student_academic_profile_id", UUID.class),
        rs.getInt("last_applied_student_number_version"),
        rs.getString("synchronization_status"),
        rs.getString("last_error_code"));
  }
}
