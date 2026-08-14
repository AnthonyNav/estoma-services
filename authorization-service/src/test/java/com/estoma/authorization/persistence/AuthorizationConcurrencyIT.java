package com.estoma.authorization.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.SystemAccessRepository;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccess;
import com.estoma.authorization.domain.model.SystemCode;
import com.estoma.authorization.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

class AuthorizationConcurrencyIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
  private static final UUID ALUMNO_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final UUID SUPERADMIN_ID = UUID.fromString("00000000-0000-0000-0000-000000000205");
  private static final UUID LAVADO_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

  @Autowired private AccountRoleRepository accountRoles;
  @Autowired private SystemAccessRepository systemAccesses;

  @Test
  void concurrentRoleAssignmentsAllowExactlyOneCommitForTheSameAccount() throws Exception {
    UUID accountId = UUID.randomUUID();
    ActorRef actor = ActorRef.system("academic");

    AccountRoleAssignment first =
        AccountRoleAssignment.createNew(accountId, ALUMNO_ID, RoleCode.ALUMNO, actor, NOW);
    AccountRoleAssignment second =
        AccountRoleAssignment.createNew(accountId, SUPERADMIN_ID, RoleCode.SUPERADMIN, actor, NOW);

    assertExactlyOneWriteSucceeds(
        () -> accountRoles.insert(first),
        () -> accountRoles.insert(second));

    assertThat(accountRoles.findByAccountId(accountId)).isPresent();
  }

  @Test
  void concurrentSystemAccessCreationAllowsExactlyOneCommitForTheSameAccountAndSystem()
      throws Exception {
    UUID accountId = UUID.randomUUID();
    ActorRef actor = ActorRef.system("authorization");

    SystemAccess first =
        SystemAccess.createNew(
            UUID.randomUUID(),
            accountId,
            LAVADO_ID,
            SystemCode.LAVADO_ULTRASONICO,
            actor,
            NOW);
    SystemAccess second =
        SystemAccess.createNew(
            UUID.randomUUID(),
            accountId,
            LAVADO_ID,
            SystemCode.LAVADO_ULTRASONICO,
            actor,
            NOW);

    assertExactlyOneWriteSucceeds(
        () -> systemAccesses.insert(first),
        () -> systemAccesses.insert(second));

    assertThat(systemAccesses.findByAccountId(accountId)).hasSize(1);
  }

  private static void assertExactlyOneWriteSucceeds(Runnable first, Runnable second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try {
      Future<Boolean> firstResult = executor.submit(() -> runConcurrentWrite(first, ready, start));
      Future<Boolean> secondResult = executor.submit(() -> runConcurrentWrite(second, ready, start));

      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();

      List<Boolean> results = List.of(firstResult.get(), secondResult.get());
      assertThat(results.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
    }
  }

  private static boolean runConcurrentWrite(
      Runnable action, CountDownLatch ready, CountDownLatch start) throws InterruptedException {
    ready.countDown();
    if (!start.await(5, TimeUnit.SECONDS)) {
      throw new IllegalStateException("concurrent write start barrier timed out");
    }

    try {
      action.run();
      return true;
    } catch (DataIntegrityViolationException expectedUniqueConflict) {
      return false;
    }
  }
}
