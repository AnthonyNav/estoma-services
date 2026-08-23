package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.ProjectionResetRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcProjectionResetRepository implements ProjectionResetRepository {
  private final JdbcClient jdbc;
  public JdbcProjectionResetRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override
  public void reset(String projectionName) {
    switch (projectionName) {
      case "academic" -> {
        jdbc.sql("DELETE FROM student_course_section_projection").update();
        jdbc.sql("DELETE FROM course_section_projection").update();
        jdbc.sql("DELETE FROM student_academic_profile_projection").update();
        jdbc.sql("DELETE FROM academic_period_projection").update();
      }
      case "authorization" -> {
        jdbc.sql("DELETE FROM wash_system_access_projection").update();
        jdbc.sql("DELETE FROM account_role_projection").update();
      }
      case "wash-administration" -> {
        jdbc.sql("DELETE FROM resource_priority_projection").update();
        jdbc.sql("DELETE FROM tank_projection").update();
        jdbc.sql("DELETE FROM cabin_projection").update();
        jdbc.sql("DELETE FROM administrative_closure_projection").update();
        jdbc.sql("DELETE FROM service_schedule_projection").update();
        jdbc.sql("DELETE FROM service_calendar_projection").update();
      }
      case "wash-execution" -> {
        jdbc.sql("DELETE FROM wash_execution_evidence_projection").update();
        jdbc.sql("DELETE FROM wash_execution_transition_projection").update();
        jdbc.sql("DELETE FROM cleaning_period_projection").update();
        jdbc.sql("DELETE FROM resource_unavailability_projection").update();
      }
      default -> throw new IllegalArgumentException("unknown projection " + projectionName);
    }
  }
}
