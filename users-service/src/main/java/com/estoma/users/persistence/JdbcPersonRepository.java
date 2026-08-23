package com.estoma.users.persistence;

import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.PersonStatus;
import com.estoma.users.domain.RejectionCode;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPersonRepository implements PersonRepository {

  private static final String COLUMNS = """
      person_id, first_name, paternal_surname, maternal_surname,
      institutional_email, institutional_email_normalized, phone,
      status, version, created_at, updated_at, deactivated_at
      """;

  private final JdbcClient jdbc;

  JdbcPersonRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Person> findById(UUID personId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM persons WHERE person_id = :personId")
        .param("personId", personId)
        .query(PersonRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<Person> findByNormalizedEmail(String normalizedEmail) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM persons WHERE institutional_email_normalized = :email")
        .param("email", normalizedEmail)
        .query(PersonRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<Person> listAfter(UUID cursorExclusive, int limit) {
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive");
    }
    if (cursorExclusive == null) {
      return jdbc.sql("SELECT " + COLUMNS + " FROM persons ORDER BY person_id LIMIT :limit")
          .param("limit", limit)
          .query(PersonRowMapper.INSTANCE)
          .list();
    }
    return jdbc.sql("SELECT " + COLUMNS + " FROM persons WHERE person_id > :cursor ORDER BY person_id LIMIT :limit")
        .param("cursor", cursorExclusive)
        .param("limit", limit)
        .query(PersonRowMapper.INSTANCE)
        .list();
  }

  @Override
  public void insert(Person person) {
    try {
      jdbc.sql("""
              INSERT INTO persons (
                person_id, first_name, paternal_surname, maternal_surname,
                institutional_email, institutional_email_normalized, phone,
                status, version, created_at, updated_at, deactivated_at
              ) VALUES (
                :personId, :firstName, :paternalSurname, :maternalSurname,
                :email, :emailNormalized, :phone,
                CAST(:status AS person_status), :version, :createdAt, :updatedAt, :deactivatedAt
              )
              """)
          .param("personId", person.personId())
          .param("firstName", person.firstName())
          .param("paternalSurname", person.paternalSurname())
          .param("maternalSurname", person.maternalSurname())
          .param("email", person.email().value())
          .param("emailNormalized", person.email().normalized())
          .param("phone", person.phone())
          .param("status", person.status().name())
          .param("version", person.version())
          .param("createdAt", Timestamps.toTimestamp(person.createdAt()))
          .param("updatedAt", Timestamps.toTimestamp(person.updatedAt()))
          .param("deactivatedAt", Timestamps.toTimestamp(person.deactivatedAt()))
          .update();
    } catch (DuplicateKeyException e) {
      throw translateInsertConflict(e);
    }
  }

  @Override
  public int updateProfile(Person updated, int expectedVersion) {
    try {
      return jdbc.sql("""
              UPDATE persons SET
                first_name = :firstName,
                paternal_surname = :paternalSurname,
                maternal_surname = :maternalSurname,
                institutional_email = :email,
                institutional_email_normalized = :emailNormalized,
                phone = :phone,
                version = :newVersion,
                updated_at = :updatedAt
              WHERE person_id = :personId AND version = :expectedVersion
              """)
          .param("firstName", updated.firstName())
          .param("paternalSurname", updated.paternalSurname())
          .param("maternalSurname", updated.maternalSurname())
          .param("email", updated.email().value())
          .param("emailNormalized", updated.email().normalized())
          .param("phone", updated.phone())
          .param("newVersion", updated.version())
          .param("updatedAt", Timestamps.toTimestamp(updated.updatedAt()))
          .param("personId", updated.personId())
          .param("expectedVersion", expectedVersion)
          .update();
    } catch (DuplicateKeyException e) {
      throw new PersonRejectedException(RejectionCode.DUPLICATE_PERSON,
          "institutional email is already claimed by a different person");
    }
  }

  @Override
  public int updateStatus(Person updated, int expectedVersion, PersonStatus expectedCurrentStatus) {
    return jdbc.sql("""
            UPDATE persons SET
              status = CAST(:newStatus AS person_status),
              deactivated_at = :deactivatedAt,
              version = :newVersion,
              updated_at = :updatedAt
            WHERE person_id = :personId
              AND version = :expectedVersion
              AND status = CAST(:expectedCurrentStatus AS person_status)
            """)
        .param("newStatus", updated.status().name())
        .param("deactivatedAt", Timestamps.toTimestamp(updated.deactivatedAt()))
        .param("newVersion", updated.version())
        .param("updatedAt", Timestamps.toTimestamp(updated.updatedAt()))
        .param("personId", updated.personId())
        .param("expectedVersion", expectedVersion)
        .param("expectedCurrentStatus", expectedCurrentStatus.name())
        .update();
  }

  private static PersonRejectedException translateInsertConflict(DuplicateKeyException e) {
    String message = e.getMostSpecificCause().getMessage();
    if (message != null && message.contains("persons_pkey")) {
      return new PersonRejectedException(RejectionCode.PERSON_ALREADY_EXISTS,
          "a person with this personId already exists");
    }
    if (message != null && message.contains("ux_persons_email_normalized")) {
      return new PersonRejectedException(RejectionCode.DUPLICATE_PERSON,
          "institutional email is already claimed by a different person");
    }
    throw e;
  }
}
