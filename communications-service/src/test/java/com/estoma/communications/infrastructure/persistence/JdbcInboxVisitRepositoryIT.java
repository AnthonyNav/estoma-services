package com.estoma.communications.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.InboxVisitRepository;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcInboxVisitRepositoryIT extends AbstractPostgresIT {

  @Autowired private InboxVisitRepository repository;

  @Test
  void firstVisitIsApplied() {
    UUID accountId = UUID.randomUUID();

    boolean applied = repository.upsertIfNewer(accountId, "LAVADO_ULTRASONICO", Instant.now(), Instant.now());

    assertThat(applied).isTrue();
  }

  @Test
  void aDelayedVisitDoesNotOverwriteAMoreRecentOne() {
    UUID accountId = UUID.randomUUID();
    Instant now = Instant.now();
    repository.upsertIfNewer(accountId, "LAVADO_ULTRASONICO", now, now);

    boolean applied = repository.upsertIfNewer(accountId, "LAVADO_ULTRASONICO", now.minusSeconds(3600), now);

    assertThat(applied).isFalse();
  }

  @Test
  void aNewerVisitOverwritesAnOlderOne() {
    UUID accountId = UUID.randomUUID();
    Instant now = Instant.now();
    repository.upsertIfNewer(accountId, "LAVADO_ULTRASONICO", now, now);

    boolean applied = repository.upsertIfNewer(accountId, "LAVADO_ULTRASONICO", now.plusSeconds(60), now.plusSeconds(60));

    assertThat(applied).isTrue();
  }
}
