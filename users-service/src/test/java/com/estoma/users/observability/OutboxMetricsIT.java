package com.estoma.users.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.persistence.JdbcOutboxRepository;
import com.estoma.users.testsupport.AbstractPostgresIT;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class OutboxMetricsIT extends AbstractPostgresIT {

  @Autowired private JdbcOutboxRepository outboxRepository;
  @Autowired private OutboxMetrics metrics;
  @Autowired private MeterRegistry meterRegistry;

  @Test
  void refreshesGaugesWhenInvokedExplicitly() {
    appendPendingMessage();

    metrics.refresh();

    assertThat(meterRegistry.get("estoma.outbox.pending").gauge().value()).isEqualTo(1);
    assertThat(meterRegistry.get("estoma.outbox.stuck_processing").gauge().value()).isZero();
  }

  private void appendPendingMessage() {
    UUID personId = UUID.randomUUID();
    outboxRepository.append(
        new OutboxAppendRequest(
            UUID.randomUUID(),
            "PersonCreated",
            1,
            "EVENT",
            personId,
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "{\"personId\":\"" + personId + "\"}",
            null,
            Instant.now()));
  }
}
