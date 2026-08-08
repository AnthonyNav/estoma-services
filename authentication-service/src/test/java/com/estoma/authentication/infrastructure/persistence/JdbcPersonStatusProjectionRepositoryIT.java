package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcPersonStatusProjectionRepositoryIT extends AbstractPostgresIT {

  @Autowired private PersonStatusProjectionRepository repository;

  @Test
  void firstUpsertAlwaysApplies() {
    UUID personId = UUID.randomUUID();

    boolean applied = repository.upsertIfNewer(personId, "ACTIVE", 1, Instant.now());

    assertThat(applied).isTrue();
    assertThat(repository.findByPersonId(personId).orElseThrow().status()).isEqualTo("ACTIVE");
  }

  @Test
  void staleOrEqualSourceVersionIsIgnored() {
    UUID personId = UUID.randomUUID();
    repository.upsertIfNewer(personId, "ACTIVE", 3, Instant.now());

    boolean appliedEqual = repository.upsertIfNewer(personId, "INACTIVE", 3, Instant.now());
    boolean appliedStale = repository.upsertIfNewer(personId, "INACTIVE", 2, Instant.now());

    assertThat(appliedEqual).isFalse();
    assertThat(appliedStale).isFalse();
    assertThat(repository.findByPersonId(personId).orElseThrow().status()).isEqualTo("ACTIVE");
  }

  @Test
  void newerSourceVersionApplies() {
    UUID personId = UUID.randomUUID();
    repository.upsertIfNewer(personId, "ACTIVE", 1, Instant.now());

    boolean applied = repository.upsertIfNewer(personId, "INACTIVE", 2, Instant.now());

    assertThat(applied).isTrue();
    assertThat(repository.findByPersonId(personId).orElseThrow().status()).isEqualTo("INACTIVE");
  }
}
