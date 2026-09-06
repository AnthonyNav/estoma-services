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
  public Optional<UUID> findAccountIdByStudentEnrollment(String studentEnrollment) {
    return jdbc.sql("""
        SELECT account_id
        FROM student_academic_profile_read_model
        WHERE student_number = :studentEnrollment AND academic_status = 'ACTIVE'
        """)
        .param("studentEnrollment", studentEnrollment)
        .query(UUID.class)
        .optional();
  }

  @Override
  public Optional<Appointment> findPrimaryAppointment(UUID accountId, LocalDate serviceDate) {
    return jdbc.sql("""
        SELECT a.appointment_id, a.status AS appointment_status, a.source_version AS appointment_version,
               a.appointment_type,
               a.instrument_count, a.piece_type, a.course_section_id, c.nrc AS course_nrc,
               c.name AS course_name, a.appointment_time_slot_id, ats.starts_at, ats.ends_at,
               ats.timezone, ats.cancellation_deadline_at, ats.arrival_tolerance_until,
               a.exceptional_authorization_id, a.exceptional_authorization_context_known,
               e.wash_execution_id,
               e.status AS wash_execution_status, e.execution_version AS wash_execution_version,
               e.arrived_at AS wash_execution_arrived_at,
               e.rejection_reason AS wash_execution_rejection_reason, e.exit_submitted_at,
               e.submitted_package_count, e.submitted_green_paper_cassette_8_count,
               e.submitted_green_paper_cassette_10_count, e.submitted_witness_tape_portion_count,
               e.completed_at, e.final_package_count, e.final_green_paper_cassette_8_count,
               e.final_green_paper_cassette_10_count, e.final_witness_tape_portion_count,
               ra.resource_assignment_id, ra.cabin_id, cabin.code AS cabin_code,
               cabin.name AS cabin_name, ra.tank_id, tank.code AS tank_code, tank.name AS tank_name,
               last_ra.resource_assignment_id AS last_resource_assignment_id,
               last_ra.cabin_id AS last_cabin_id, last_cabin.code AS last_cabin_code,
               last_cabin.name AS last_cabin_name, last_ra.tank_id AS last_tank_id,
               last_tank.code AS last_tank_code, last_tank.name AS last_tank_name
        FROM appointment_read_model a
        JOIN appointment_time_slot_read_model ats
          ON ats.appointment_time_slot_id = a.appointment_time_slot_id
        LEFT JOIN course_section_read_model c ON c.course_section_id = a.course_section_id
        LEFT JOIN LATERAL (
          SELECT wash_execution_id, status, execution_version, arrived_at, rejection_reason,
                 exit_submitted_at, submitted_package_count,
                 submitted_green_paper_cassette_8_count, submitted_green_paper_cassette_10_count,
                 submitted_witness_tape_portion_count, completed_at, final_package_count,
                 final_green_paper_cassette_8_count, final_green_paper_cassette_10_count,
                 final_witness_tape_portion_count
          FROM wash_execution_read_model
          WHERE appointment_id = a.appointment_id
          ORDER BY updated_at DESC, wash_execution_id ASC
          LIMIT 1
        ) e ON TRUE
        LEFT JOIN LATERAL (
          SELECT resource_assignment_id, cabin_id, tank_id
          FROM wash_resource_assignment_read_model
          WHERE wash_execution_id = e.wash_execution_id AND released_at IS NULL
          ORDER BY assigned_at DESC NULLS LAST, resource_assignment_id ASC
          LIMIT 1
        ) ra ON TRUE
        LEFT JOIN wash_cabin_read_model cabin ON cabin.cabin_id = ra.cabin_id
        LEFT JOIN wash_tank_read_model tank ON tank.tank_id = ra.tank_id
        LEFT JOIN LATERAL (
          SELECT resource_assignment_id, cabin_id, tank_id
          FROM wash_resource_assignment_read_model
          WHERE wash_execution_id = e.wash_execution_id AND released_at IS NOT NULL
          ORDER BY released_at DESC, assigned_at DESC NULLS LAST, resource_assignment_id ASC
          LIMIT 1
        ) last_ra ON TRUE
        LEFT JOIN wash_cabin_read_model last_cabin ON last_cabin.cabin_id = last_ra.cabin_id
        LEFT JOIN wash_tank_read_model last_tank ON last_tank.tank_id = last_ra.tank_id
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
            rs.getString("appointment_status"), rs.getLong("appointment_version"),
            rs.getString("appointment_type"),
            integer(rs, "instrument_count"), rs.getString("piece_type"),
            uuid(rs, "course_section_id"), rs.getString("course_nrc"), rs.getString("course_name"),
            uuid(rs, "appointment_time_slot_id"), instant(rs, "starts_at"), instant(rs, "ends_at"),
            rs.getString("timezone"), instant(rs, "cancellation_deadline_at"),
            instant(rs, "arrival_tolerance_until"), uuid(rs, "exceptional_authorization_id"),
            rs.getBoolean("exceptional_authorization_context_known"),
            uuid(rs, "wash_execution_id"), rs.getString("wash_execution_status"),
            longObject(rs, "wash_execution_version"), instant(rs, "wash_execution_arrived_at"),
            rs.getString("wash_execution_rejection_reason"), instant(rs, "exit_submitted_at"),
            integer(rs, "submitted_package_count"), integer(rs, "submitted_green_paper_cassette_8_count"),
            integer(rs, "submitted_green_paper_cassette_10_count"),
            integer(rs, "submitted_witness_tape_portion_count"), instant(rs, "completed_at"),
            integer(rs, "final_package_count"), integer(rs, "final_green_paper_cassette_8_count"),
            integer(rs, "final_green_paper_cassette_10_count"),
            integer(rs, "final_witness_tape_portion_count"), uuid(rs, "resource_assignment_id"),
            uuid(rs, "cabin_id"), rs.getString("cabin_code"), rs.getString("cabin_name"),
            uuid(rs, "tank_id"), rs.getString("tank_code"), rs.getString("tank_name"),
            uuid(rs, "last_resource_assignment_id"), uuid(rs, "last_cabin_id"),
            rs.getString("last_cabin_code"), rs.getString("last_cabin_name"),
            uuid(rs, "last_tank_id"), rs.getString("last_tank_code"), rs.getString("last_tank_name")))
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

  private static Long longObject(ResultSet rs, String column) throws SQLException {
    return rs.getObject(column, Long.class);
  }

  private static Instant instant(ResultSet rs, String column) throws SQLException {
    return JdbcTimes.instant(rs, column);
  }
}
