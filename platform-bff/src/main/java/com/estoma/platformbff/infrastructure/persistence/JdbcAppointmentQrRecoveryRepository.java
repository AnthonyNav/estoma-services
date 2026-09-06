package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.AppointmentQrRecoveryRepository;
import com.estoma.platformbff.domain.projection.AppointmentQrRecoveryState;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAppointmentQrRecoveryRepository implements AppointmentQrRecoveryRepository {
  private final JdbcClient jdbc;

  public JdbcAppointmentQrRecoveryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void lock(LocalDate serviceDate) {
    jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('appointment-qr-recovery:' || CAST(:date AS TEXT), 0))")
        .param("date", serviceDate).query().singleRow();
  }

  @Override
  public long nextGeneration() {
    return jdbc.sql("SELECT nextval('appointment_qr_recovery_generation_seq')")
        .query(Long.class).single();
  }

  @Override
  public void insert(AppointmentQrRecoveryState s) {
    jdbc.sql("""
        INSERT INTO appointment_qr_recovery_state (
          service_date, generation, restart_revision, recovery_job_id, pass_number, page_number, page_token,
          current_query_message_id, status, started_at, updated_at, completed_at, last_error_code)
        VALUES (:date, :generation, :restartRevision, :jobId, :pass, :page, :token, :queryId, :status,
          :startedAt, :updatedAt, :completedAt, :error)
        """).param("date", s.serviceDate()).param("generation", s.generation())
        .param("restartRevision", s.restartRevision()).param("jobId", s.recoveryJobId()).param("pass", s.passNumber())
        .param("page", s.pageNumber()).param("token", s.pageToken())
        .param("queryId", s.currentQueryMessageId()).param("status", s.status())
        .param("startedAt", JdbcTimes.timestamp(s.startedAt()))
        .param("updatedAt", JdbcTimes.timestamp(s.updatedAt()))
        .param("completedAt", JdbcTimes.timestamp(s.completedAt())).param("error", s.lastErrorCode())
        .update();
  }

  @Override
  public void update(AppointmentQrRecoveryState s) {
    int changed = jdbc.sql("""
        UPDATE appointment_qr_recovery_state SET pass_number = :pass, page_number = :page,
          page_token = :token, current_query_message_id = :queryId, status = :status,
          updated_at = :updatedAt, completed_at = :completedAt, last_error_code = :error
        WHERE service_date = :date AND generation = :generation AND recovery_job_id = :jobId
        """).param("pass", s.passNumber()).param("page", s.pageNumber())
        .param("token", s.pageToken()).param("queryId", s.currentQueryMessageId())
        .param("status", s.status()).param("updatedAt", JdbcTimes.timestamp(s.updatedAt()))
        .param("completedAt", JdbcTimes.timestamp(s.completedAt())).param("error", s.lastErrorCode())
        .param("date", s.serviceDate()).param("generation", s.generation())
        .param("jobId", s.recoveryJobId()).update();
    if (changed != 1) throw new IllegalStateException("appointment QR recovery state changed concurrently");
  }

  @Override
  public void replace(long expectedGeneration, AppointmentQrRecoveryState s) {
    int changed = jdbc.sql("""
        UPDATE appointment_qr_recovery_state SET generation = :generation, restart_revision = :restartRevision,
          recovery_job_id = :jobId, pass_number = :pass, page_number = :page,
          page_token = :token, current_query_message_id = :queryId, status = :status,
          started_at = :startedAt, updated_at = :updatedAt, completed_at = :completedAt,
          last_error_code = :error
        WHERE service_date = :date AND generation = :expectedGeneration
        """).param("generation", s.generation()).param("restartRevision", s.restartRevision()).param("jobId", s.recoveryJobId())
        .param("pass", s.passNumber()).param("page", s.pageNumber()).param("token", s.pageToken())
        .param("queryId", s.currentQueryMessageId()).param("status", s.status())
        .param("startedAt", JdbcTimes.timestamp(s.startedAt()))
        .param("updatedAt", JdbcTimes.timestamp(s.updatedAt()))
        .param("completedAt", JdbcTimes.timestamp(s.completedAt())).param("error", s.lastErrorCode())
        .param("date", s.serviceDate()).param("expectedGeneration", expectedGeneration).update();
    if (changed != 1) throw new IllegalStateException("appointment QR recovery restart raced");
  }

  @Override
  public Optional<AppointmentQrRecoveryState> find(LocalDate serviceDate) {
    return jdbc.sql("SELECT * FROM appointment_qr_recovery_state WHERE service_date = :date")
        .param("date", serviceDate).query((rs, row) -> map(rs)).optional();
  }

  @Override
  public Optional<AppointmentQrRecoveryState> findByJobId(UUID recoveryJobId) {
    return jdbc.sql("SELECT * FROM appointment_qr_recovery_state WHERE recovery_job_id = :jobId")
        .param("jobId", recoveryJobId).query((rs, row) -> map(rs)).optional();
  }

  @Override
  public boolean hasPageToken(UUID recoveryJobId, int passNumber, String pageToken) {
    return jdbc.sql("""
        SELECT EXISTS(SELECT 1 FROM appointment_qr_recovery_page_tokens
          WHERE recovery_job_id = :jobId AND pass_number = :pass AND page_token = :token)
        """).param("jobId", recoveryJobId).param("pass", passNumber).param("token", pageToken)
        .query(Boolean.class).single();
  }

  @Override
  public boolean recordPageToken(UUID recoveryJobId, int passNumber, String pageToken) {
    return jdbc.sql("""
        INSERT INTO appointment_qr_recovery_page_tokens(recovery_job_id, pass_number, page_token)
        VALUES (:jobId, :pass, :token)
        ON CONFLICT DO NOTHING
        """).param("jobId", recoveryJobId).param("pass", passNumber).param("token", pageToken)
        .update() == 1;
  }

  private static AppointmentQrRecoveryState map(ResultSet rs) throws SQLException {
    return new AppointmentQrRecoveryState(rs.getObject("service_date", LocalDate.class),
        rs.getLong("generation"), rs.getLong("restart_revision"), rs.getObject("recovery_job_id", UUID.class),
        rs.getInt("pass_number"), rs.getInt("page_number"), rs.getString("page_token"),
        rs.getObject("current_query_message_id", UUID.class), rs.getString("status"),
        JdbcTimes.instant(rs, "started_at"), JdbcTimes.instant(rs, "updated_at"),
        JdbcTimes.instant(rs, "completed_at"), rs.getString("last_error_code"));
  }
}
