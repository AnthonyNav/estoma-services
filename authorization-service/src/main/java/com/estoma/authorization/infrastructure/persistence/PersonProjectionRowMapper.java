package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.PersonStatusProjectionRepository.PersonStatusProjection;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

final class PersonProjectionRowMapper implements RowMapper<PersonStatusProjection> {

  static final PersonProjectionRowMapper INSTANCE = new PersonProjectionRowMapper();

  private PersonProjectionRowMapper() {}

  @Override
  public PersonStatusProjection mapRow(ResultSet rs, int rowNum) throws SQLException {
    return new PersonStatusProjection(
        rs.getObject("person_id", java.util.UUID.class),
        ProjectedStatus.valueOf(rs.getString("status")),
        rs.getInt("source_version"),
        Timestamps.toInstant(rs, "updated_at"));
  }
}
