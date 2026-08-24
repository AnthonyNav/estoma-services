package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.out.ProjectionRecoveryRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import com.estoma.appointments.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class EntityRecoveryReadinessIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");

  @Autowired WashExecutionEntityRecoveryResultProcessor processor;
  @Autowired ProjectionRecoveryRepository recovery;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("TRUNCATE TABLE processed_messages, projection_recovery_state CASCADE").update();
  }

  @Test
  void singleEntitySnapshotDoesNotPromoteGlobalExecutionProjectionToReady() {
    UUID appointmentId = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("status", "SUCCEEDED");
    var data = mapper.createObjectNode();
    data.put("appointmentId", appointmentId.toString());
    data.putNull("washExecution");
    data.set("resourceUnavailabilities", mapper.createArrayNode());
    data.set("cleaningPeriods", mapper.createArrayNode());
    payload.set("data", data);

    processor.process(new MessageEnvelope(UUID.randomUUID(),
        "GetWashExecutionIntegrationContextResult", 1, MessageKind.RESULT, "wash-execution", NOW,
        correlationId, queryId, null, null, null, null,
        new WireActor("SYSTEM", null, "wash-execution"), payload));

    var state = recovery.get("wash-execution");
    assertThat(state.status()).isEqualTo("UNINITIALIZED");
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE status='APPLIED'")
        .query(Long.class).single()).isEqualTo(1);
  }
}
