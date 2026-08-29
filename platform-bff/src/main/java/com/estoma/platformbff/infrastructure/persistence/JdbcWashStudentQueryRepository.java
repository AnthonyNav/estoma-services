package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.WashStudentQueryPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashStudentQueryRepository implements WashStudentQueryPort {
  private final JdbcClient jdbc;

  public JdbcWashStudentQueryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Student> findStudent(UUID accountId) {
    return jdbc.sql("""
        SELECT p.first_name, p.paternal_surname, p.maternal_surname,
               sp.student_number, sp.current_semester
        FROM student_academic_profile_read_model sp
        JOIN person_presentation_read_model p ON p.person_id = sp.person_id
        WHERE sp.account_id = :accountId AND sp.academic_status = 'ACTIVE'
        """)
        .param("accountId", accountId)
        .query((rs, rowNum) -> new Student(rs.getString("first_name"),
            rs.getString("paternal_surname"), rs.getString("maternal_surname"),
            rs.getString("student_number"), integer(rs, "current_semester")))
        .optional();
  }

  @Override
  public Optional<Appointment> findPrimaryAppointment(UUID accountId, LocalDate serviceDate) {
    return jdbc.sql("""
        SELECT a.appointment_id, a.status AS appointment_status, a.appointment_type,
               a.instrument_count, a.piece_type, a.course_section_id, c.nrc AS course_nrc,
               c.name AS course_name, a.appointment_time_slot_id, ats.starts_at, ats.ends_at,
               ats.timezone, ats.arrival_tolerance_until, e.wash_execution_id,
               e.status AS wash_execution_status
        FROM appointment_read_model a
        JOIN appointment_time_slot_read_model ats
          ON ats.appointment_time_slot_id = a.appointment_time_slot_id
        LEFT JOIN course_section_read_model c ON c.course_section_id = a.course_section_id
        LEFT JOIN LATERAL (
          SELECT wash_execution_id, status
          FROM wash_execution_read_model
          WHERE appointment_id = a.appointment_id
          ORDER BY updated_at DESC, wash_execution_id ASC
          LIMIT 1
        ) e ON TRUE
        WHERE a.student_account_id = :accountId AND ats.service_date = :serviceDate
        ORDER BY CASE
          WHEN a.status = 'IN_PROGRESS' THEN 0
          WHEN a.status = 'SCHEDULED' THEN 1
          ELSE 2 END,
          a.updated_at DESC, a.appointment_id ASC
        LIMIT 1
        """)
        .param("accountId", accountId)
        .param("serviceDate", serviceDate)
        .query((rs, rowNum) -> new Appointment(uuid(rs, "appointment_id"),
            rs.getString("appointment_status"), rs.getString("appointment_type"),
            integer(rs, "instrument_count"), rs.getString("piece_type"),
            uuid(rs, "course_section_id"), rs.getString("course_nrc"), rs.getString("course_name"),
            uuid(rs, "appointment_time_slot_id"), instant(rs, "starts_at"), instant(rs, "ends_at"),
            rs.getString("timezone"), instant(rs, "arrival_tolerance_until"),
            uuid(rs, "wash_execution_id"), rs.getString("wash_execution_status")))
        .optional();
  }

  @Override
  public List<CourseSection> findAvailableCourseSections(UUID accountId) {
    return jdbc.sql("""
        SELECT c.course_section_id, c.nrc, c.name
        FROM student_enrollment_read_model e
        JOIN student_academic_profile_read_model sp
          ON sp.student_academic_profile_id = e.student_academic_profile_id
        JOIN academic_period_read_model p ON p.academic_period_id = e.academic_period_id
        JOIN course_section_read_model c ON c.course_section_id = e.course_section_id
        WHERE e.account_id = :accountId
          AND sp.academic_status = 'ACTIVE'
          AND e.status = 'ACTIVE'
          AND p.status = 'ACTIVE'
          AND c.status = 'ACTIVE'
        ORDER BY c.nrc ASC, c.course_section_id ASC
        """)
        .param("accountId", accountId)
        .query((rs, rowNum) -> new CourseSection(uuid(rs, "course_section_id"),
            rs.getString("nrc"), rs.getString("name")))
        .list();
  }

  private static UUID uuid(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, UUID.class);
  }

  private static Integer integer(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, Integer.class);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return JdbcTimes.instant(rs, column);
  }
}
