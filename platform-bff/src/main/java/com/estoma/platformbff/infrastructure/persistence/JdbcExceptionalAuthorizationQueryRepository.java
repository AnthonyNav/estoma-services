package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.ExceptionalAuthorizationQueryPort;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExceptionalAuthorizationQueryRepository implements ExceptionalAuthorizationQueryPort {
  private final JdbcClient jdbc;
  public JdbcExceptionalAuthorizationQueryRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  public List<Student> search(String query) {
    return jdbc.sql("""
        SELECT DISTINCT sp.account_id, sp.student_number,
          concat_ws(' ', p.first_name, p.paternal_surname, p.maternal_surname) AS full_name
        FROM student_academic_profile_read_model sp
        JOIN person_presentation_read_model p ON p.person_id = sp.person_id
        WHERE sp.academic_status = 'ACTIVE' AND sp.account_id IS NOT NULL
          AND (strpos(lower(sp.student_number), lower(:query)) > 0
            OR strpos(lower(concat_ws(' ', p.first_name, p.paternal_surname, p.maternal_surname)), lower(:query)) > 0)
        ORDER BY sp.student_number, sp.account_id LIMIT 30
        """).param("query", query).query((rs, n) -> new Student(rs.getObject("account_id", UUID.class),
            rs.getString("student_number"), rs.getString("full_name"))).list();
  }

  public List<Authorization> list(UUID studentAccountId, LocalDate date) {
    return jdbc.sql("""
        SELECT a.*, EXISTS (SELECT 1 FROM exceptional_authorization_supersession_read_model s
            WHERE s.authorization_id = a.authorization_id) AS superseded,
          EXISTS (SELECT 1 FROM appointment_read_model ap
            WHERE ap.exceptional_authorization_id = a.authorization_id
              AND NOT (ap.status = 'CANCELLED' AND COALESCE(ap.cancellation_type, '') = 'CLINIC')) AS consumed
        FROM exceptional_appointment_authorization_read_model a
        WHERE student_account_id = :student AND service_date = :date
        ORDER BY updated_at DESC, authorization_id
        """).param("student", studentAccountId).param("date", date)
        .query((rs, n) -> new Authorization(rs.getObject("authorization_id", UUID.class),
            rs.getString("status"), rs.getString("reason"), JdbcTimes.instant(rs, "expires_at"),
            rs.getBoolean("policy_context_known"), rs.getBoolean("superseded"), rs.getBoolean("consumed"))).list();
  }
}
