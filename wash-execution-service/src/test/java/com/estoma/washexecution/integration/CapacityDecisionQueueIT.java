package com.estoma.washexecution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washexecution.application.port.ResourceConcurrencyRepository;
import com.estoma.washexecution.infrastructure.messaging.inbound.TransientFailureClassifier;
import com.estoma.washexecution.testsupport.AbstractPostgresIT;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class CapacityDecisionQueueIT extends AbstractPostgresIT {
  @Autowired ResourceConcurrencyRepository locks;
  @Autowired PlatformTransactionManager transactionManager;

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void contendersQueueBeforeEligibilityLocksAndHaveBoundedTransactionalLifetime(boolean timeout)
      throws Exception {
    CountDownLatch acquired = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var holder = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
        locks.lockCapacityDecision();
        acquired.countDown();
        try {
          if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("holder timed out");
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(interrupted);
        }
      }));
      try {
        assertThat(acquired.await(2, TimeUnit.SECONDS)).isTrue();
        var contender = executor.submit(() -> new TransactionTemplate(transactionManager)
            .executeWithoutResult(tx -> locks.lockCapacityDecision()));
        long deadline = System.nanoTime() + 400_000_000L;
        boolean waiting = false;
        while (System.nanoTime() < deadline && !contender.isDone()) {
          waiting = jdbc.queryForObject(
              "SELECT count(*) FROM pg_locks WHERE locktype='advisory' AND NOT granted", Integer.class) > 0;
          if (waiting) break;
          Thread.sleep(5);
        }
        assertThat(waiting).as("contender waits in database instead of failing NOWAIT against another cancellation").isTrue();
        // Plain reads remain allowed even while the first cancellation owns the gate.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM cabin_projection", Integer.class)).isZero();
        if (timeout) {
          assertThatThrownBy(() -> contender.get(2, TimeUnit.SECONDS))
              .hasCauseInstanceOf(DataAccessException.class)
              .satisfies(error -> assertThat(new TransientFailureClassifier().isTransient(error)).isTrue());
        } else {
          release.countDown();
          contender.get(2, TimeUnit.SECONDS);
        }
      } finally {
        release.countDown();
        holder.get(2, TimeUnit.SECONDS);
      }
    }
    // Neither commit nor rollback leaves session-level advisory state on pooled connections.
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory'", Integer.class)).isZero();
    new TransactionTemplate(transactionManager).executeWithoutResult(tx -> locks.lockCapacityDecision());
    assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE locktype='advisory'", Integer.class)).isZero();
  }
}
