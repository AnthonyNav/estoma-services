package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.ExceptionalAppointmentAuthorizationRepository;
import com.estoma.appointments.domain.model.ExceptionalAppointmentAuthorization;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExceptionalAppointmentAuthorizationRepository implements ExceptionalAppointmentAuthorizationRepository {
  private final JdbcClient jdbc;
  public JdbcExceptionalAppointmentAuthorizationRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public ExceptionalAppointmentAuthorization save(ExceptionalAppointmentAuthorization a) {
    jdbc.sql("INSERT INTO exceptional_appointment_authorizations (exceptional_authorization_id,student_account_id,service_date,granted_at,expires_at,cancelled,version,created_at,updated_at) VALUES (:id,:student,:date,:granted,:expires,:cancelled,:version,now(),now())")
      .param("id", a.exceptionalAuthorizationId()).param("student", a.studentAccountId()).param("date", a.serviceDate())
      .param("granted", Timestamps.toTimestamp(a.grantedAt())).param("expires", Timestamps.toTimestamp(a.expiresAt())).param("cancelled", a.isCancelled()).param("version", a.version()).update();
    return a;
  }
  @Override public Optional<ExceptionalAppointmentAuthorization> findById(UUID id) { return find("exceptional_authorization_id=:id", "id", id, false); }
  @Override public Optional<ExceptionalAppointmentAuthorization> findByIdForUpdate(UUID id) { return find("exceptional_authorization_id=:id", "id", id, true); }
  @Override public Optional<ExceptionalAppointmentAuthorization> findByStudentAndDate(UUID student, LocalDate date) {
    return jdbc.sql("SELECT exceptional_authorization_id,student_account_id,service_date,granted_at,expires_at,cancelled,version FROM exceptional_appointment_authorizations WHERE student_account_id=:student AND service_date=:date")
        .param("student", student).param("date", date).query(JdbcExceptionalAppointmentAuthorizationRepository::map).optional();
  }
  private Optional<ExceptionalAppointmentAuthorization> find(String predicate, String name, Object value, boolean lock) {
    return jdbc.sql("SELECT exceptional_authorization_id,student_account_id,service_date,granted_at,expires_at,cancelled,version FROM exceptional_appointment_authorizations WHERE " + predicate + (lock ? " FOR UPDATE" : ""))
        .param(name, value).query(JdbcExceptionalAppointmentAuthorizationRepository::map).optional();
  }
  private static ExceptionalAppointmentAuthorization map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return ExceptionalAppointmentAuthorization.reconstitute(rs.getObject("exceptional_authorization_id", UUID.class), rs.getObject("student_account_id", UUID.class), rs.getObject("service_date", LocalDate.class), Timestamps.toInstant(rs, "granted_at"), Timestamps.toInstant(rs, "expires_at"), rs.getBoolean("cancelled"), rs.getLong("version"));
  }
  @Override public boolean updateIfVersion(ExceptionalAppointmentAuthorization a, long expectedVersion) {
    return jdbc.sql("UPDATE exceptional_appointment_authorizations SET expires_at=:expires,cancelled=:cancelled,version=:nextVersion,updated_at=now() WHERE exceptional_authorization_id=:id AND version=:expectedVersion")
        .param("expires", Timestamps.toTimestamp(a.expiresAt())).param("cancelled", a.isCancelled()).param("nextVersion", a.version()).param("id", a.exceptionalAuthorizationId()).param("expectedVersion", expectedVersion).update() == 1;
  }
}
