package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.StudentNumberSyncRepository;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcStudentNumberSyncRepositoryIT extends AbstractPostgresIT {

  @Autowired private StudentNumberSyncRepository repository;

  @Test
  void ensureExistsInitializesPendingRow() {
    UUID accountId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();

    repository.ensureExists(accountId, profileId, Instant.now());

    var record = repository.findByAccountId(accountId).orElseThrow();
    assertThat(record.synchronizationStatus()).isEqualTo("PENDING");
    assertThat(record.lastAppliedStudentNumberVersion()).isZero();
  }

  @Test
  void ensureExistsIsNoOpWhenRowAlreadyPresent() {
    UUID accountId = UUID.randomUUID();
    UUID profileId = UUID.randomUUID();
    repository.ensureExists(accountId, profileId, Instant.now());
    repository.markSynchronized(accountId, 1, Instant.now());

    repository.ensureExists(accountId, profileId, Instant.now());

    assertThat(repository.findByAccountId(accountId).orElseThrow().synchronizationStatus()).isEqualTo("SYNCHRONIZED");
  }

  @Test
  void markSynchronizedClearsPriorError() {
    UUID accountId = UUID.randomUUID();
    repository.ensureExists(accountId, UUID.randomUUID(), Instant.now());
    repository.markFailed(accountId, "SOURCE_VERSION_GAP", Instant.now());

    repository.markSynchronized(accountId, 2, Instant.now());

    var record = repository.findByAccountId(accountId).orElseThrow();
    assertThat(record.synchronizationStatus()).isEqualTo("SYNCHRONIZED");
    assertThat(record.lastErrorCode()).isNull();
    assertThat(record.lastAppliedStudentNumberVersion()).isEqualTo(2);
  }
}
