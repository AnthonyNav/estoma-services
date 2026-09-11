package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.appointments.application.port.out.ProjectionRecoveryJobRepository;
import com.estoma.appointments.application.port.out.ProjectionRecoveryRepository;
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

class RecoveryContextJsonIT extends AbstractPostgresIT {
  private static final Instant NOW = Instant.parse("2026-09-11T13:00:00Z");
  @Autowired ProjectionRecoveryResultProcessor processor;
  @Autowired ProjectionRecoveryJobRepository jobs;
  @Autowired ProjectionRecoveryRepository states;
  @Autowired ProjectionRecoveryCoordinator coordinator;
  @Autowired JdbcClient jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach void clean() {
    jdbc.sql("TRUNCATE processed_messages,projection_recovery_jobs,projection_recovery_state,"
        + "pending_projection_events,outbox_messages CASCADE").update();
  }

  @Test void resumesPagedExecutionRecoveryFromPostgresJsonbAndCompletesIt() {
    UUID generation = UUID.randomUUID();
    UUID firstQuery = UUID.randomUUID();
    coordinator.rebuilding("wash-execution");
    jobs.tryStart(new ProjectionRecoveryJobRepository.RecoveryJob("wash-execution", generation,
        "PAGE", firstQuery, null, "{\"serviceDate\":\"2026-09-10\"}", NOW, NOW));
    assertThat(jobs.find("wash-execution").orElseThrow().contextJson()).contains(": ");
    processor.process(result(generation, firstQuery, "2026-09-10", "next-page"));
    var next = jobs.find("wash-execution").orElseThrow();
    assertThat(next.currentQueryMessageId()).isNotEqualTo(firstQuery);
    var payload = jdbc.sql("SELECT payload::text FROM outbox_messages WHERE message_id=:id")
        .param("id",next.currentQueryMessageId()).query(String.class).single();
    assertThat(mapper.readTree(payload).path("serviceDate").asString()).isEqualTo("2026-09-10");
    assertThat(mapper.readTree(payload).path("pageToken").asString()).isEqualTo("next-page");
    processor.process(result(generation, next.currentQueryMessageId(), "2026-09-10", null));
    assertThat(jobs.find("wash-execution")).isEmpty();
    assertThat(states.get("wash-execution").status()).isEqualTo("READY");
  }

  @Test void stillRejectsSnapshotsForAnotherServiceDate() {
    UUID generation = UUID.randomUUID();
    UUID query = UUID.randomUUID();
    coordinator.rebuilding("wash-execution");
    jobs.tryStart(new ProjectionRecoveryJobRepository.RecoveryJob("wash-execution", generation,
        "PAGE", query, null, "{\"serviceDate\":\"2026-09-10\"}", NOW, NOW));
    assertThatThrownBy(() -> processor.process(result(generation,query,"2026-09-11",null)))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("serviceDate mismatch");
    assertThat(states.get("wash-execution").status()).isEqualTo("REBUILDING");
    assertThat(jobs.find("wash-execution").orElseThrow().currentQueryMessageId()).isEqualTo(query);
  }

  @Test void academicPaginationReadsPostgresJsonbContext() {
    UUID period = UUID.randomUUID();
    var context = jdbc.sql("SELECT CAST(:context AS jsonb)::text")
        .param("context", "{\"academicPeriodId\":\"" + period + "\"}")
        .query(String.class).single();
    var payload = mapper.readTree(ProjectionRecoveryWorker.pagePayload("academic", "next", context));
    assertThat(payload.path("academicPeriodId").asString()).isEqualTo(period.toString());
  }

  private MessageEnvelope result(UUID generation, UUID query, String date, String cursor) {
    var payload = mapper.createObjectNode().put("status", "SUCCEEDED");
    var data = payload.putObject("data");
    data.put("serviceDate",date).put("snapshotGeneratedAt",NOW.toString());
    data.putArray("executions");
    data.putArray("resourceUnavailabilities");
    data.putArray("cleaningPeriods");
    if(cursor != null) data.put("nextPageToken",cursor);
    return new MessageEnvelope(UUID.randomUUID(),"ListWashExecutionIntegrationContextsPageResult",1,
        MessageKind.RESULT,"wash-execution",NOW,generation,query,query,null,null,null,
        new WireActor("SYSTEM",null,"appointments"),payload);
  }
}
