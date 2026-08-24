package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.application.port.out.ProjectionRecoveryJobRepository;
import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
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

class ProjectionRecoveryFencingIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-08-23T15:00:00Z");

  @Autowired ProjectionRecoveryResultProcessor processor;
  @Autowired ProjectionRecoveryJobRepository jobs;
  @Autowired ProjectionRecoveryCoordinator recovery;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clean() {
    jdbc.sql("""
        TRUNCATE TABLE projection_recovery_jobs, projection_recovery_state,
          processed_messages, service_calendar_projection, service_schedule_projection,
          administrative_closure_projection, cabin_projection, tank_projection,
          resource_priority_projection, outbox_messages CASCADE
        """).update();
  }

  @Test
  void resultFromPreviousQueryCannotAdvanceCurrentRecoveryJob() {
    recovery.rebuilding("wash-administration");
    UUID generation = UUID.randomUUID();
    UUID currentQuery = UUID.randomUUID();
    jobs.start(new ProjectionRecoveryJobRepository.RecoveryJob(
        "wash-administration", generation, "CONFIG", currentQuery, null, "{}", NOW, NOW));

    UUID staleQuery = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("status", "SUCCEEDED");
    payload.set("data", mapper.createObjectNode());
    processor.process(new MessageEnvelope(UUID.randomUUID(),
        "GetWashAdministrationConfigurationResult", 1, MessageKind.RESULT,
        "wash-administration", NOW.plusSeconds(1), UUID.randomUUID(), staleQuery,
        null, null, null, null, new WireActor("SYSTEM", null, "wash-administration"), payload));

    var job = jobs.find("wash-administration").orElseThrow();
    assertThat(job.generationId()).isEqualTo(generation);
    assertThat(job.currentQueryMessageId()).isEqualTo(currentQuery);
    assertThat(recovery.state("wash-administration").status()).isEqualTo("REBUILDING");
    assertThat(jdbc.sql("SELECT count(*) FROM service_calendar_projection")
        .query(Long.class).single()).isZero();
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE status='APPLIED'")
        .query(Long.class).single()).isEqualTo(1);
  }
}
