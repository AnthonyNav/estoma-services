package com.estoma.users.persistence;

import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;

/** Maps one row of {@code persons} back into a {@link Person}. */
final class PersonRowMapper implements RowMapper<Person> {

  static final PersonRowMapper INSTANCE = new PersonRowMapper();

  private PersonRowMapper() {}

  @Override
  public Person mapRow(ResultSet rs, int rowNum) throws SQLException {
    return Person.reconstitute(
        rs.getObject("person_id", UUID.class),
        rs.getString("first_name"),
        rs.getString("paternal_surname"),
        rs.getString("maternal_surname"),
        new InstitutionalEmail(
            rs.getString("institutional_email"), rs.getString("institutional_email_normalized")),
        rs.getString("phone"),
        PersonStatus.valueOf(rs.getString("status")),
        rs.getInt("version"),
        toInstant(rs, "created_at"),
        toInstant(rs, "updated_at"),
        toInstant(rs, "deactivated_at"));
  }

  private static java.time.Instant toInstant(ResultSet rs, String column) throws SQLException {
    OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
    return value == null ? null : value.toInstant();
  }
}
