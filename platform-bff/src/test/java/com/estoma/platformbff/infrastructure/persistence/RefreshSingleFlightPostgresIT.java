package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = {
    "platform.outbox.enabled=false",
    "platform.messaging.listeners-enabled=false",
    "platform.messaging.topology-declare=false"
})
@Tag("integration")
class RefreshSingleFlightPostgresIT {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_db")
      .withUsername("bff")
      .withPassword("bff");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired JdbcRefreshSingleFlight singleFlight;

  @Test
  void sameSessionIsSerializedAcrossConcurrentTransactions() throws Exception {
    UUID sessionId = UUID.randomUUID();
    AtomicInteger concurrent = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var calls = java.util.stream.IntStream.range(0, 2)
          .mapToObj(ignored -> executor.submit(() -> {
            ready.countDown();
            if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
            return singleFlight.execute(sessionId, () -> {
              int active = concurrent.incrementAndGet();
              maximum.accumulateAndGet(active, Math::max);
              try {
                Thread.sleep(150);
                return "ok";
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
              } finally {
                concurrent.decrementAndGet();
              }
            });
          }))
          .toList();
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      for (var call : calls) assertThat(call.get(10, TimeUnit.SECONDS)).isEqualTo("ok");
    }

    assertThat(maximum).hasValue(1);
  }
}
