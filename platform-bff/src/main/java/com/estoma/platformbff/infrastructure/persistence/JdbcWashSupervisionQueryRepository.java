package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.WashSupervisionQueryPort;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashSupervisionQueryRepository implements WashSupervisionQueryPort {
  private final JdbcClient jdbc;

  public JdbcWashSupervisionQueryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public List<PendingReassignment> findPendingReassignments() {
    return jdbc.sql("""
        SELECT e.wash_execution_id, e.status AS execution_status, e.authorized_at,
               e.execution_version,
               a.appointment_id, a.appointment_type, a.instrument_count, a.piece_type,
               a.status AS appointment_status, a.appointment_time_slot_id,
               ats.service_date, ats.starts_at AS slot_starts_at, ats.ends_at AS slot_ends_at,
               ats.timezone AS slot_timezone,
               a.course_section_id, c.nrc AS course_nrc, c.name AS course_name,
               a.student_account_id,
               sp.person_id, p.first_name, p.paternal_surname, p.maternal_surname,
               sp.student_number, sp.current_semester, sp.academic_status,
               ra.resource_assignment_id, ra.assignment_type, ra.assigned_at,
               ra.cabin_id, cabin.code AS cabin_code, cabin.name AS cabin_name,
               ra.tank_id, tank.code AS tank_code, tank.name AS tank_name,
               e.submitted_package_count,
               e.submitted_green_paper_cassette_8_count,
               e.submitted_green_paper_cassette_10_count,
               e.submitted_witness_tape_portion_count
        FROM wash_execution_read_model e
        LEFT JOIN appointment_read_model a ON a.appointment_id = e.appointment_id
        LEFT JOIN appointment_time_slot_read_model ats
          ON ats.appointment_time_slot_id = a.appointment_time_slot_id
        LEFT JOIN student_academic_profile_read_model sp ON sp.account_id = a.student_account_id
        LEFT JOIN person_presentation_read_model p ON p.person_id = sp.person_id
        LEFT JOIN course_section_read_model c ON c.course_section_id = a.course_section_id
        LEFT JOIN LATERAL (
          SELECT resource_assignment_id, assignment_type, assigned_at, cabin_id, tank_id
          FROM wash_resource_assignment_read_model
          WHERE wash_execution_id = e.wash_execution_id AND released_at IS NULL
          ORDER BY assigned_at DESC NULLS LAST, resource_assignment_id ASC
          LIMIT 1
        ) ra ON TRUE
        LEFT JOIN wash_cabin_read_model cabin ON cabin.cabin_id = ra.cabin_id
        LEFT JOIN wash_tank_read_model tank ON tank.tank_id = ra.tank_id
        WHERE e.status = 'PENDING_REASSIGNMENT'
        ORDER BY e.authorized_at ASC NULLS LAST, e.wash_execution_id ASC
        """)
        .query((rs, rowNum) -> row(rs))
        .list();
  }

  private static PendingReassignment row(ResultSet rs) throws SQLException {
    return new PendingReassignment(
        uuid(rs, "wash_execution_id"), rs.getString("execution_status"),
        instant(rs, "authorized_at"), rs.getLong("execution_version"),
        uuid(rs, "appointment_id"), rs.getString("appointment_type"),
        integer(rs, "instrument_count"), rs.getString("piece_type"),
        rs.getString("appointment_status"), uuid(rs, "appointment_time_slot_id"),
        rs.getObject("service_date", LocalDate.class), instant(rs, "slot_starts_at"),
        instant(rs, "slot_ends_at"), rs.getString("slot_timezone"),
        uuid(rs, "course_section_id"), rs.getString("course_nrc"), rs.getString("course_name"),
        uuid(rs, "student_account_id"), uuid(rs, "person_id"), rs.getString("first_name"),
        rs.getString("paternal_surname"), rs.getString("maternal_surname"),
        rs.getString("student_number"), integer(rs, "current_semester"), rs.getString("academic_status"),
        uuid(rs, "resource_assignment_id"), rs.getString("assignment_type"), instant(rs, "assigned_at"),
        uuid(rs, "cabin_id"), rs.getString("cabin_code"), rs.getString("cabin_name"),
        uuid(rs, "tank_id"), rs.getString("tank_code"), rs.getString("tank_name"),
        integer(rs, "submitted_package_count"), integer(rs, "submitted_green_paper_cassette_8_count"),
        integer(rs, "submitted_green_paper_cassette_10_count"),
        integer(rs, "submitted_witness_tape_portion_count"));
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
