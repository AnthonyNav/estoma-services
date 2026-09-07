package com.estoma.platformbff.infrastructure.persistence;

import com.estoma.platformbff.application.port.WashSupervisionQueryPort;
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
public class JdbcWashSupervisionQueryRepository implements WashSupervisionQueryPort {
  private final JdbcClient jdbc;

  public JdbcWashSupervisionQueryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public HomeSnapshot summarizeHome(LocalDate serviceDate) {
    return jdbc.sql("""
        SELECT COUNT(DISTINCT a.appointment_id) AS registered_appointments,
               COUNT(DISTINCT a.appointment_id) FILTER (
                 WHERE e.status IN ('PENDING_ENTRY', 'PENDING_REASSIGNMENT', 'IN_PROGRESS', 'EXIT_SUBMITTED')
               ) AS in_process_appointments,
               COUNT(DISTINCT a.appointment_id) FILTER (WHERE a.status = 'COMPLETED')
                 AS completed_appointments,
               COUNT(DISTINCT a.appointment_id) FILTER (WHERE a.status = 'ENTRY_REJECTED')
                 AS denied_appointments,
               COUNT(DISTINCT a.appointment_id) FILTER (WHERE a.status = 'CANCELLED')
                 AS cancelled_appointments,
               (SELECT COUNT(*) FROM wash_execution_read_model
                WHERE status = 'PENDING_REASSIGNMENT') AS pending_reassignments_count
        FROM appointment_read_model a
        JOIN appointment_time_slot_read_model ats
          ON ats.appointment_time_slot_id = a.appointment_time_slot_id
        LEFT JOIN wash_execution_read_model e ON e.appointment_id = a.appointment_id
        WHERE ats.service_date = :serviceDate
        """)
        .param("serviceDate", serviceDate)
        .query((rs, rowNum) -> new HomeSnapshot(
            rs.getLong("registered_appointments"), rs.getLong("in_process_appointments"),
            rs.getLong("completed_appointments"), rs.getLong("denied_appointments"),
            rs.getLong("cancelled_appointments"), rs.getLong("pending_reassignments_count")))
        .single();
  }

  @Override
  public List<LookupCandidate> findAppointments(UUID studentAccountId, LocalDate serviceDate) {
    return findAppointments(studentAccountId, null, serviceDate);
  }

  @Override
  public Optional<LookupCandidate> findAppointment(UUID appointmentId, LocalDate serviceDate) {
    return findAppointments(null, appointmentId, serviceDate).stream().findFirst();
  }

  @Override
  public Optional<ExecutionDetail> findExecution(UUID washExecutionId) {
    return jdbc.sql("""
        SELECT e.wash_execution_id, e.status AS execution_status, e.execution_version,
               e.arrived_at, e.rejection_reason, e.exit_submitted_at, e.completed_at,
               e.submitted_package_count, e.submitted_green_paper_cassette_8_count,
               e.submitted_green_paper_cassette_10_count,
               e.submitted_witness_tape_portion_count,
               e.final_package_count, e.final_green_paper_cassette_8_count,
               e.final_green_paper_cassette_10_count, e.final_witness_tape_portion_count,
               a.appointment_id, a.source_version AS appointment_version,
               a.status AS appointment_status, a.appointment_type, a.instrument_count,
               a.piece_type, a.course_section_id,
               c.nrc AS course_nrc, c.name AS course_name,
               a.appointment_time_slot_id, ats.service_date,
               ats.starts_at AS slot_starts_at, ats.ends_at AS slot_ends_at,
               ats.timezone AS slot_timezone,
               a.student_account_id, sp.person_id, p.first_name, p.paternal_surname,
               p.maternal_surname, sp.student_number, sp.current_semester, sp.academic_status,
               (SELECT COUNT(*) FROM wash_resource_assignment_read_model counted_ra
                WHERE counted_ra.wash_execution_id = e.wash_execution_id
                  AND counted_ra.released_at IS NULL) AS active_assignment_count,
               active_ra.resource_assignment_id AS active_resource_assignment_id,
               active_ra.assignment_type AS active_assignment_type,
               active_ra.cabin_id AS active_cabin_id, active_cabin.code AS active_cabin_code,
               active_cabin.name AS active_cabin_name,
               active_ra.tank_id AS active_tank_id, active_tank.code AS active_tank_code,
               active_tank.name AS active_tank_name,
               last_ra.resource_assignment_id AS last_resource_assignment_id,
               last_ra.assignment_type AS last_assignment_type,
               last_ra.cabin_id AS last_cabin_id, last_cabin.code AS last_cabin_code,
               last_cabin.name AS last_cabin_name,
               last_ra.tank_id AS last_tank_id, last_tank.code AS last_tank_code,
               last_tank.name AS last_tank_name
        FROM wash_execution_read_model e
        LEFT JOIN appointment_read_model a ON a.appointment_id = e.appointment_id
        LEFT JOIN appointment_time_slot_read_model ats
          ON ats.appointment_time_slot_id = a.appointment_time_slot_id
        LEFT JOIN LATERAL (
          SELECT person_id, student_number, current_semester, academic_status
          FROM student_academic_profile_read_model
          WHERE account_id = a.student_account_id
          ORDER BY CASE WHEN academic_status = 'ACTIVE' THEN 0 ELSE 1 END,
                   updated_at DESC, student_academic_profile_id ASC
          LIMIT 1
        ) sp ON TRUE
        LEFT JOIN person_presentation_read_model p ON p.person_id = sp.person_id
        LEFT JOIN course_section_read_model c ON c.course_section_id = a.course_section_id
        LEFT JOIN LATERAL (
          SELECT resource_assignment_id, assignment_type, cabin_id, tank_id
          FROM wash_resource_assignment_read_model
          WHERE wash_execution_id = e.wash_execution_id AND released_at IS NULL
          ORDER BY assigned_at DESC NULLS LAST, resource_assignment_id ASC
          LIMIT 1
        ) active_ra ON TRUE
        LEFT JOIN wash_cabin_read_model active_cabin ON active_cabin.cabin_id = active_ra.cabin_id
        LEFT JOIN wash_tank_read_model active_tank ON active_tank.tank_id = active_ra.tank_id
        LEFT JOIN LATERAL (
          SELECT resource_assignment_id, assignment_type, cabin_id, tank_id
          FROM wash_resource_assignment_read_model
          WHERE wash_execution_id = e.wash_execution_id AND released_at IS NOT NULL
          ORDER BY released_at DESC, assigned_at DESC NULLS LAST, resource_assignment_id ASC
          LIMIT 1
        ) last_ra ON TRUE
        LEFT JOIN wash_cabin_read_model last_cabin ON last_cabin.cabin_id = last_ra.cabin_id
        LEFT JOIN wash_tank_read_model last_tank ON last_tank.tank_id = last_ra.tank_id
        WHERE e.wash_execution_id = :washExecutionId
        """)
        .param("washExecutionId", washExecutionId)
        .query((rs, rowNum) -> executionRow(rs))
        .optional();
  }

  private List<LookupCandidate> findAppointments(UUID studentAccountId, UUID appointmentId,
      LocalDate serviceDate) {
    return jdbc.sql("""
        SELECT a.student_account_id, sp.person_id, p.first_name, p.paternal_surname,
               p.maternal_surname, sp.student_number, sp.current_semester, sp.academic_status,
               a.appointment_id, a.source_version AS appointment_version,
               a.status AS appointment_status, a.appointment_type,
               a.instrument_count, a.piece_type, a.course_section_id,
               c.nrc AS course_nrc, c.name AS course_name, a.appointment_time_slot_id,
               ats.starts_at AS slot_starts_at, ats.ends_at AS slot_ends_at,
               ats.timezone AS slot_timezone,
               e.wash_execution_id, e.status AS execution_status, e.execution_version,
               e.arrived_at, e.rejection_reason, e.exit_submitted_at,
               ra.resource_assignment_id, ra.assignment_type,
               ra.cabin_id, cabin.code AS cabin_code, cabin.name AS cabin_name,
               ra.tank_id, tank.code AS tank_code, tank.name AS tank_name,
               e.submitted_package_count, e.submitted_green_paper_cassette_8_count,
               e.submitted_green_paper_cassette_10_count,
               e.submitted_witness_tape_portion_count
        FROM appointment_read_model a
        JOIN appointment_time_slot_read_model ats
          ON ats.appointment_time_slot_id = a.appointment_time_slot_id
        LEFT JOIN LATERAL (
          SELECT person_id, student_number, current_semester, academic_status
          FROM student_academic_profile_read_model
          WHERE account_id = a.student_account_id
          ORDER BY CASE WHEN academic_status = 'ACTIVE' THEN 0 ELSE 1 END,
                   updated_at DESC, student_academic_profile_id ASC
          LIMIT 1
        ) sp ON TRUE
        LEFT JOIN person_presentation_read_model p ON p.person_id = sp.person_id
        LEFT JOIN course_section_read_model c ON c.course_section_id = a.course_section_id
        LEFT JOIN LATERAL (
          SELECT wash_execution_id, status, execution_version, arrived_at, rejection_reason,
                 exit_submitted_at, submitted_package_count,
                 submitted_green_paper_cassette_8_count,
                 submitted_green_paper_cassette_10_count,
                 submitted_witness_tape_portion_count, updated_at
          FROM wash_execution_read_model
          WHERE appointment_id = a.appointment_id
          ORDER BY updated_at DESC, wash_execution_id ASC
          LIMIT 1
        ) e ON TRUE
        LEFT JOIN LATERAL (
          SELECT resource_assignment_id, assignment_type, cabin_id, tank_id
          FROM wash_resource_assignment_read_model
          WHERE wash_execution_id = e.wash_execution_id AND released_at IS NULL
          ORDER BY assigned_at DESC NULLS LAST, resource_assignment_id ASC
          LIMIT 1
        ) ra ON TRUE
        LEFT JOIN wash_cabin_read_model cabin ON cabin.cabin_id = ra.cabin_id
        LEFT JOIN wash_tank_read_model tank ON tank.tank_id = ra.tank_id
        WHERE ats.service_date = :serviceDate
          AND (:byStudent = FALSE OR a.student_account_id = :studentAccountId)
          AND (:byAppointment = FALSE OR a.appointment_id = :appointmentId)
        ORDER BY a.updated_at DESC, a.appointment_id ASC
        """)
        .param("byStudent", studentAccountId != null)
        .param("studentAccountId", studentAccountId == null ? new UUID(0, 0) : studentAccountId)
        .param("byAppointment", appointmentId != null)
        .param("appointmentId", appointmentId == null ? new UUID(0, 0) : appointmentId)
        .param("serviceDate", serviceDate)
        .query((rs, rowNum) -> lookupRow(rs))
        .list();
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

  private static LookupCandidate lookupRow(ResultSet rs) throws SQLException {
    return new LookupCandidate(
        uuid(rs, "student_account_id"), uuid(rs, "person_id"), rs.getString("first_name"),
        rs.getString("paternal_surname"), rs.getString("maternal_surname"),
        rs.getString("student_number"), integer(rs, "current_semester"), rs.getString("academic_status"),
        uuid(rs, "appointment_id"), rs.getObject("appointment_version", Long.class),
        rs.getString("appointment_status"), rs.getString("appointment_type"),
        integer(rs, "instrument_count"), rs.getString("piece_type"), uuid(rs, "course_section_id"),
        rs.getString("course_nrc"), rs.getString("course_name"), uuid(rs, "appointment_time_slot_id"),
        instant(rs, "slot_starts_at"), instant(rs, "slot_ends_at"), rs.getString("slot_timezone"),
        uuid(rs, "wash_execution_id"), rs.getString("execution_status"), longObject(rs, "execution_version"),
        instant(rs, "arrived_at"), rs.getString("rejection_reason"), instant(rs, "exit_submitted_at"),
        uuid(rs, "resource_assignment_id"), rs.getString("assignment_type"), uuid(rs, "cabin_id"),
        rs.getString("cabin_code"), rs.getString("cabin_name"), uuid(rs, "tank_id"),
        rs.getString("tank_code"), rs.getString("tank_name"), integer(rs, "submitted_package_count"),
        integer(rs, "submitted_green_paper_cassette_8_count"),
        integer(rs, "submitted_green_paper_cassette_10_count"),
        integer(rs, "submitted_witness_tape_portion_count"));
  }

  private static ExecutionDetail executionRow(ResultSet rs) throws SQLException {
    return new ExecutionDetail(
        uuid(rs, "wash_execution_id"), rs.getString("execution_status"),
        longObject(rs, "execution_version"), instant(rs, "arrived_at"),
        rs.getString("rejection_reason"), instant(rs, "exit_submitted_at"),
        instant(rs, "completed_at"), uuid(rs, "appointment_id"),
        longObject(rs, "appointment_version"), rs.getString("appointment_status"),
        rs.getString("appointment_type"), integer(rs, "instrument_count"),
        rs.getString("piece_type"), uuid(rs, "course_section_id"),
        rs.getString("course_nrc"), rs.getString("course_name"),
        uuid(rs, "appointment_time_slot_id"), rs.getObject("service_date", LocalDate.class),
        instant(rs, "slot_starts_at"), instant(rs, "slot_ends_at"),
        rs.getString("slot_timezone"), uuid(rs, "student_account_id"),
        uuid(rs, "person_id"), rs.getString("first_name"),
        rs.getString("paternal_surname"), rs.getString("maternal_surname"),
        rs.getString("student_number"), integer(rs, "current_semester"),
        rs.getString("academic_status"), rs.getLong("active_assignment_count"),
        uuid(rs, "active_resource_assignment_id"),
        rs.getString("active_assignment_type"), uuid(rs, "active_cabin_id"),
        rs.getString("active_cabin_code"), rs.getString("active_cabin_name"),
        uuid(rs, "active_tank_id"), rs.getString("active_tank_code"),
        rs.getString("active_tank_name"), uuid(rs, "last_resource_assignment_id"),
        rs.getString("last_assignment_type"), uuid(rs, "last_cabin_id"),
        rs.getString("last_cabin_code"), rs.getString("last_cabin_name"),
        uuid(rs, "last_tank_id"), rs.getString("last_tank_code"),
        rs.getString("last_tank_name"), integer(rs, "submitted_package_count"),
        integer(rs, "submitted_green_paper_cassette_8_count"),
        integer(rs, "submitted_green_paper_cassette_10_count"),
        integer(rs, "submitted_witness_tape_portion_count"), integer(rs, "final_package_count"),
        integer(rs, "final_green_paper_cassette_8_count"),
        integer(rs, "final_green_paper_cassette_10_count"),
        integer(rs, "final_witness_tape_portion_count"));
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
