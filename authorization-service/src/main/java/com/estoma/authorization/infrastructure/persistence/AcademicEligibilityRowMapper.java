package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository.AcademicEligibilityProjection;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class AcademicEligibilityRowMapper implements RowMapper<AcademicEligibilityProjection> {

  static final AcademicEligibilityRowMapper INSTANCE = new AcademicEligibilityRowMapper();

  private AcademicEligibilityRowMapper() {}

  @Override
  public AcademicEligibilityProjection mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new AcademicEligibilityProjection(
        rs.getObject("eligibility_id", java.util.UUID.class),
        rs.getObject("student_academic_profile_id", java.util.UUID.class),
        rs.getString("system_code"),
        rs.getObject("account_id", java.util.UUID.class),
        rs.getBoolean("eligible"),
        rs.getInt("policy_version"),
        rs.getInt("eligibility_version"),
        rs.getInt("academic_profile_version"),
        rs.getString("pending_reason"),
        Timestamps.toInstant(rs, "evaluated_at"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}
