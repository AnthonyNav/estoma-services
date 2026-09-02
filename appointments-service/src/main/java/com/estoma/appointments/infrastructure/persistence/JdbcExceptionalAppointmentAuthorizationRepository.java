package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExceptionalAppointmentAuthorizationRepository implements ExceptionalAppointmentAuthorizationRepository {
  private static final String COLUMNS = "exceptional_authorization_id,student_account_id,service_date,authorized_by_account_id,reason,granted_at,expires_at,cancelled_by_account_id,cancellation_reason,cancelled_at,superseded_by_authorization_id,superseded_at,version";
  private final JdbcClient jdbc;
  public JdbcExceptionalAppointmentAuthorizationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public ExceptionalAppointmentAuthorization save(ExceptionalAppointmentAuthorization a) {
    jdbc.sql("""
      INSERT INTO exceptional_appointment_authorizations
        (exceptional_authorization_id,student_account_id,service_date,authorized_by_account_id,reason,
         granted_at,expires_at,cancelled_by_account_id,cancellation_reason,cancelled_at,
         superseded_by_authorization_id,superseded_at,version,created_at,updated_at)
      VALUES (:id,:student,:date,:authorizedBy,:reason,:granted,:expires,:cancelledBy,:cancellationReason,
              :cancelledAt,:supersededBy,:supersededAt,:version,now(),now())
      """)
      .param("id", a.exceptionalAuthorizationId())
      .param("student", a.studentAccountId())
      .param("date", a.serviceDate())
      .param("authorizedBy", a.authorizedByAccountId())
      .param("reason", a.reason())
      .param("granted", Timestamps.toTimestamp(a.grantedAt()))
      .param("expires", null)
      .param("cancelledBy", a.cancelledByAccountId())
      .param("cancellationReason", a.cancellationReason())
      .param("cancelledAt", Timestamps.toTimestamp(a.cancelledAt()))
      .param("supersededBy", a.supersededByAuthorizationId())
      .param("supersededAt", Timestamps.toTimestamp(a.supersededAt()))
      .param("version", a.version())
      .update();
    return a;
  }

  @Override public Optional<ExceptionalAppointmentAuthorization> findById(UUID id) {
    return find("exceptional_authorization_id=:id", "id", id, false);
  }

  @Override public Optional<ExceptionalAppointmentAuthorization> findByIdForUpdate(UUID id) {
    return find("exceptional_authorization_id=:id", "id", id, true);
  }

  @Override public List<ExceptionalAppointmentAuthorization> findByStudentAndDate(UUID student, LocalDate date) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM exceptional_appointment_authorizations WHERE student_account_id=:student AND service_date=:date")
        .param("student", student).param("date", date).query(JdbcExceptionalAppointmentAuthorizationRepository::map).list();
  }

  private Optional<ExceptionalAppointmentAuthorization> find(String predicate, String name, Object value, boolean lock) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM exceptional_appointment_authorizations WHERE " + predicate + (lock ? " FOR UPDATE" : ""))
        .param(name, value).query(JdbcExceptionalAppointmentAuthorizationRepository::map).optional();
  }

  private static ExceptionalAppointmentAuthorization map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return ExceptionalAppointmentAuthorization.reconstitute(
        rs.getObject("exceptional_authorization_id", UUID.class),
        rs.getObject("student_account_id", UUID.class),
        rs.getObject("service_date", LocalDate.class),
        rs.getObject("authorized_by_account_id", UUID.class),
        rs.getString("reason"),
        Timestamps.toInstant(rs, "granted_at"),
        Timestamps.toInstant(rs, "expires_at"),
        rs.getObject("cancelled_by_account_id", UUID.class),
        rs.getString("cancellation_reason"),
        Timestamps.toInstant(rs, "cancelled_at"),
        rs.getObject("superseded_by_authorization_id", UUID.class),
        Timestamps.toInstant(rs, "superseded_at"),
        rs.getLong("version"));
  }

  @Override public boolean updateIfVersion(ExceptionalAppointmentAuthorization a, long expectedVersion) {
    return jdbc.sql("""
      UPDATE exceptional_appointment_authorizations SET
        expires_at=:expires,
        cancelled_by_account_id=:cancelledBy,
        cancellation_reason=:cancellationReason,
        cancelled_at=:cancelledAt,
        superseded_by_authorization_id=:supersededBy,
        superseded_at=:supersededAt,
        version=:nextVersion,
        updated_at=now()
      WHERE exceptional_authorization_id=:id AND version=:expectedVersion
      """)
      .param("expires", null)
      .param("cancelledBy", a.cancelledByAccountId())
      .param("cancellationReason", a.cancellationReason())
      .param("cancelledAt", Timestamps.toTimestamp(a.cancelledAt()))
      .param("supersededBy", a.supersededByAuthorizationId())
      .param("supersededAt", Timestamps.toTimestamp(a.supersededAt()))
      .param("nextVersion", a.version())
      .param("id", a.exceptionalAuthorizationId())
      .param("expectedVersion", expectedVersion)
      .update() == 1;
  }
}
