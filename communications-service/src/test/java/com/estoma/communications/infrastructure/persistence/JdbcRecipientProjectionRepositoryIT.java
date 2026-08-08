package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcRecipientProjectionRepositoryIT extends AbstractPostgresIT {

  @Autowired private RecipientProjectionRepository repository;

  @Test
  void accountEventCreatesRowWithoutPersonData() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();

    boolean applied = repository.upsertAccountIfNewer(accountId, personId, "ACTIVE", 1, Instant.now());

    assertThat(applied).isTrue();
    RecipientProjectionRepository.RecipientProjectionRecord record = repository.findByAccountId(accountId).orElseThrow();
    assertThat(record.accountStatus()).isEqualTo("ACTIVE");
    assertThat(record.personStatus()).isNull();
    assertThat(record.personSourceVersion()).isNull();
  }

  @Test
  void staleAccountVersionIsIgnored() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    repository.upsertAccountIfNewer(accountId, personId, "ACTIVE", 5, Instant.now());

    boolean applied = repository.upsertAccountIfNewer(accountId, personId, "INACTIVE", 3, Instant.now());

    assertThat(applied).isFalse();
    assertThat(repository.findByAccountId(accountId).orElseThrow().accountStatus()).isEqualTo("ACTIVE");
  }

  @Test
  void personEventOnlyUpdatesExistingRows() {
    UUID personId = UUID.randomUUID();

    int rowsAffected = repository.updatePersonIfNewer(personId, "Alumno Uno", "alumno@estoma.edu", "ACTIVE", 1, Instant.now());

    assertThat(rowsAffected).isZero();
  }

  @Test
  void personEventUpdatesEveryAccountForThatPerson() {
    UUID personId = UUID.randomUUID();
    UUID accountOne = UUID.randomUUID();
    UUID accountTwo = UUID.randomUUID();
    repository.upsertAccountIfNewer(accountOne, personId, "ACTIVE", 1, Instant.now());
    repository.upsertAccountIfNewer(accountTwo, personId, "ACTIVE", 1, Instant.now());

    int rowsAffected = repository.updatePersonIfNewer(personId, "Alumno Uno", "alumno@estoma.edu", "ACTIVE", 1, Instant.now());

    assertThat(rowsAffected).isEqualTo(2);
    assertThat(repository.findByAccountId(accountOne).orElseThrow().institutionalEmail()).isEqualTo("alumno@estoma.edu");
    assertThat(repository.findByAccountId(accountTwo).orElseThrow().displayName()).isEqualTo("Alumno Uno");
  }
}
