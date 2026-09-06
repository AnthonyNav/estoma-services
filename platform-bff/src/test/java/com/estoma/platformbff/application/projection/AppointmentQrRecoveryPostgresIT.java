package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.AppointmentQrProjectionPort;
import com.estoma.platformbff.application.port.AppointmentQrRecoveryRepository;
import com.estoma.platformbff.application.port.ProjectionRecoveryJobRepository;
import com.estoma.platformbff.application.port.ProjectionRecoveryQueryRepository;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.domain.projection.AppointmentQrRecoveryState;
import com.estoma.platformbff.domain.projection.RecoveryJob;
import com.estoma.platformbff.domain.projection.RecoveryQuery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
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
    "platform.messaging.topology-declare=false",
    "platform.recovery.appointment-qr.enabled=false",
    "platform.recovery.appointment-qr.page-size=2"
})
@Tag("integration")
class AppointmentQrRecoveryPostgresIT {
  private static final LocalDate DATE = LocalDate.of(2026, 9, 6);
  private static final Instant NOW = Instant.parse("2026-09-06T18:00:00Z");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("bff_db").withUsername("bff").withPassword("bff");

  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired AppointmentQrRecoveryService service;
  @Autowired AppointmentQrRecoveryRepository recoveries;
  @Autowired ProjectionRecoveryJobRepository jobs;
  @Autowired ProjectionRecoveryQueryRepository queries;
  @Autowired AppointmentQrProjectionPort projection;
  @Autowired JdbcClient jdbc;

  @BeforeEach
  void clear() {
    jdbc.sql("""
        TRUNCATE TABLE appointment_qr_recovery_page_tokens, appointment_qr_recovery_state,
          projection_recovery_messages, projection_recovery_jobs, inbox_messages,
          appointment_qr_projection CASCADE
        """).update();
  }

  @Test
  void completesTwoPassesAndKeepsReadinessScopedToServiceDate() {
    UUID activeId = UUID.randomUUID();
    UUID terminalId = UUID.randomUUID();
    UUID jobId = service.start(DATE);
    AppointmentQrRecoveryState first = state(DATE);
    RecoveryQuery firstQuery = query(first);
    assertThat(firstQuery.payload()).containsEntry("serviceDate", DATE.toString())
        .containsEntry("pageSize", 2).doesNotContainKey("pageToken");

    service.applyResult(succeeded(first, List.of(
        item(activeId, 4, "SCHEDULED", " opaque token "),
        item(terminalId, 7, "COMPLETED", null)), "next"));
    AppointmentQrRecoveryState second = state(DATE);
    assertThat(second.passNumber()).isEqualTo(1);
    assertThat(second.pageNumber()).isEqualTo(2);
    assertThat(query(second).payload()).containsEntry("pageToken", "next");
    assertQr(activeId, 4, " opaque token ");
    assertQr(terminalId, 7, null);

    service.applyResult(succeeded(second, List.of(), null));
    AppointmentQrRecoveryState third = state(DATE);
    assertThat(third.passNumber()).isEqualTo(2);
    assertThat(third.pageNumber()).isEqualTo(1);
    assertThat(service.isReady(DATE)).isFalse();

    service.applyResult(succeeded(third, List.of(
        item(activeId, 4, "IN_PROGRESS", " opaque token "),
        item(terminalId, 7, "CANCELLED", null)), null));
    assertThat(service.isReady(DATE)).isTrue();
    assertThat(jobs.findById(jobId).orElseThrow().status()).isEqualTo("COMPLETED");
    assertThat(service.isReady(DATE.plusDays(1))).isFalse();
  }

  @Test
  void sameVersionConflictPreflightsWholePageAndDegradesDurably() {
    UUID wouldAdvance = new UUID(0, 1);
    UUID conflicts = new UUID(0, 2);
    projection.project(wouldAdvance, "before-one", 2, NOW);
    projection.project(conflicts, "before-two", 3, NOW);
    service.start(DATE);
    AppointmentQrRecoveryState state = state(DATE);

    service.applyResult(succeeded(state, List.of(
        item(wouldAdvance, 3, "SCHEDULED", "after-one"),
        item(conflicts, 3, "SCHEDULED", "different-same-version")), null));

    assertQr(wouldAdvance, 2, "before-one");
    assertQr(conflicts, 3, "before-two");
    assertThat(state(DATE).status()).isEqualTo("DEGRADED");
    assertThat(state(DATE).lastErrorCode()).isEqualTo("SAME_VERSION_CONFLICT");
    assertThat(jobs.findById(state.recoveryJobId()).orElseThrow().status()).isEqualTo("FAILED");
  }

  @Test
  void staleSnapshotsCannotRestoreTerminalProjectionAndDuplicateResultIsIdempotent() {
    UUID id = UUID.randomUUID();
    projection.project(id, "original", 5, NOW);
    projection.invalidate(id, 6, NOW.plusSeconds(1));
    service.start(DATE);
    AppointmentQrRecoveryState state = state(DATE);
    IncomingResult result = succeeded(state,
        List.of(item(id, 5, "SCHEDULED", "stale")), null);

    assertThat(service.applyResult(result)).isTrue();
    assertThat(service.applyResult(result)).isTrue();
    assertQr(id, 6, null);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id = :id")
        .param("id", result.messageId()).query(Long.class).single()).isOne();
  }

  @Test
  void trustedMalformedCurrentResultDegradesButUntrustedEnvelopeCannotMutateJob() {
    service.start(DATE);
    AppointmentQrRecoveryState state = state(DATE);
    Map<String, Object> invalidData = new LinkedHashMap<>();
    invalidData.put("snapshotGeneratedAt", NOW.toString());
    invalidData.put("serviceDate", DATE.toString());
    invalidData.put("items", List.of());
    invalidData.put("nextPageToken", null);
    IncomingResult malformed = result(state, "SUCCEEDED",
        Map.of("status", "SUCCEEDED", "data", invalidData), ActorContext.system("appointments"));

    assertThat(service.applyResult(malformed)).isTrue();
    assertThat(state(DATE).status()).isEqualTo("DEGRADED");
    assertThat(state(DATE).lastErrorCode()).isEqualTo("INVALID_RECOVERY_RESULT");

    UUID replacementJob = service.start(DATE);
    AppointmentQrRecoveryState replacement = state(DATE);
    IncomingResult untrusted = result(replacement, "SUCCEEDED",
        successPayload(DATE, List.of(), null), ActorContext.system("intruder"));
    assertThatThrownBy(() -> service.applyResult(untrusted)).isInstanceOf(IllegalArgumentException.class);
    assertThat(state(DATE).status()).isEqualTo("RUNNING");
    assertThat(state(DATE).recoveryJobId()).isEqualTo(replacementJob);
  }

  @Test
  void rejectsOversizedPageBeforeProjectionAndPreservesCompletedJobOnExplicitRestart() {
    service.start(DATE);
    AppointmentQrRecoveryState state = state(DATE);
    List<Map<String, Object>> oversized = List.of(
        item(UUID.randomUUID(), 1, "SCHEDULED", "one"),
        item(UUID.randomUUID(), 1, "SCHEDULED", "two"),
        item(UUID.randomUUID(), 1, "SCHEDULED", "three"));
    service.applyResult(succeeded(state, oversized, null));
    assertThat(state(DATE).lastErrorCode()).isEqualTo("INVALID_RECOVERY_RESULT");
    assertThat(jdbc.sql("SELECT count(*) FROM appointment_qr_projection")
        .query(Long.class).single()).isZero();

    UUID firstJob = service.start(DATE);
    AppointmentQrRecoveryState passOne = state(DATE);
    IncomingResult lateOldGeneration = succeeded(passOne, List.of(), null);
    service.applyResult(succeeded(passOne, List.of(), null));
    service.applyResult(succeeded(state(DATE), List.of(), null));
    assertThat(jobs.findById(firstJob).orElseThrow().status()).isEqualTo("COMPLETED");

    UUID nextJob = service.start(DATE);
    assertThat(nextJob).isNotEqualTo(firstJob);
    assertThat(jobs.findById(firstJob).orElseThrow().status()).isEqualTo("COMPLETED");
    assertThat(state(DATE).generation()).isGreaterThan(passOne.generation());
    assertThat(service.applyResult(lateOldGeneration)).isTrue();
    assertThat(state(DATE).recoveryJobId()).isEqualTo(nextJob);
    assertThat(state(DATE).status()).isEqualTo("RUNNING");
  }

  @Test
  void watchdogReplacesTimedOutQueryAndRestartRevisionIsConsumedOnce() {
    service.start(DATE);
    AppointmentQrRecoveryState state = state(DATE);
    jdbc.sql("""
        UPDATE projection_recovery_messages SET status = 'PUBLISHED', published_at = :published,
          updated_at = :published WHERE message_id = :id
        """).param("published", java.sql.Timestamp.from(NOW.minusSeconds(300)))
        .param("id", state.currentQueryMessageId()).update();

    service.maintain(DATE, NOW);
    AppointmentQrRecoveryState replaced = state(DATE);
    assertThat(replaced.currentQueryMessageId()).isNotEqualTo(state.currentQueryMessageId());
    assertThat(query(replaced).payload()).isEqualTo(query(state).payload());

    service.applyResult(rejected(replaced, "QR_PROJECTION_DISABLED"));
    service.maintain(DATE, NOW.plusSeconds(600));
    assertThat(state(DATE).status()).isEqualTo("DEGRADED");
    assertThat(state(DATE).currentQueryMessageId()).isNull();

    UUID degradedJob = replaced.recoveryJobId();
    service.maintain(DATE, NOW.plusSeconds(601), 1);
    AppointmentQrRecoveryState restarted = state(DATE);
    assertThat(restarted.recoveryJobId()).isNotEqualTo(degradedJob);
    assertThat(restarted.restartRevision()).isEqualTo(1);
    service.applyResult(rejected(restarted, "QR_PROJECTION_DISABLED"));
    service.maintain(DATE, NOW.plusSeconds(602), 1);
    assertThat(state(DATE).recoveryJobId()).isEqualTo(restarted.recoveryJobId());
    assertThat(state(DATE).status()).isEqualTo("DEGRADED");
  }

  @Test
  void concurrentResultsForOneQueryAdvanceExactlyOnce() throws Exception {
    UUID appointmentId = UUID.randomUUID();
    service.start(DATE);
    AppointmentQrRecoveryState initial = state(DATE);
    IncomingResult first = succeeded(initial,
        List.of(item(appointmentId, 1, "SCHEDULED", "same")), "next");
    IncomingResult second = succeeded(initial,
        List.of(item(appointmentId, 1, "SCHEDULED", "same")), "next");
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch go = new CountDownLatch(1);

    try (var executor = Executors.newFixedThreadPool(2)) {
      var one = executor.submit(() -> applyAfterBarrier(first, ready, go));
      var two = executor.submit(() -> applyAfterBarrier(second, ready, go));
      assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
      go.countDown();
      assertThat(one.get(10, TimeUnit.SECONDS)).isTrue();
      assertThat(two.get(10, TimeUnit.SECONDS)).isTrue();
    }

    AppointmentQrRecoveryState advanced = state(DATE);
    assertThat(advanced.pageNumber()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM projection_recovery_messages WHERE recovery_job_id = :id")
        .param("id", initial.recoveryJobId()).query(Long.class).single()).isEqualTo(2);
    assertThat(jdbc.sql("SELECT count(*) FROM inbox_messages WHERE message_id IN (:one, :two)")
        .param("one", first.messageId()).param("two", second.messageId())
        .query(Long.class).single()).isOne();
    assertQr(appointmentId, 1, "same");
  }

  private boolean applyAfterBarrier(IncomingResult result, CountDownLatch ready, CountDownLatch go) {
    ready.countDown();
    try {
      if (!go.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("barrier timed out");
      return service.applyResult(result);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(interrupted);
    }
  }

  private AppointmentQrRecoveryState state(LocalDate date) {
    return recoveries.find(date).orElseThrow();
  }

  private RecoveryQuery query(AppointmentQrRecoveryState state) {
    return queries.findById(state.currentQueryMessageId()).orElseThrow();
  }

  private void assertQr(UUID id, long version, String representation) {
    Map<String, Object> row = jdbc.sql("""
        SELECT appointment_version, qr_representation FROM appointment_qr_projection
        WHERE appointment_id = :id
        """).param("id", id).query().singleRow();
    assertThat(((Number) row.get("appointment_version")).longValue()).isEqualTo(version);
    assertThat(row.get("qr_representation")).isEqualTo(representation);
  }

  private static Map<String, Object> item(UUID id, long version, String status, String qr) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("appointmentId", id.toString());
    item.put("appointmentVersion", version);
    item.put("appointmentStatus", status);
    item.put("entryAuthorizedAt", null);
    item.put("qrRepresentation", qr);
    return item;
  }

  private static IncomingResult succeeded(AppointmentQrRecoveryState state,
      List<Map<String, Object>> items, String nextPageToken) {
    return result(state, "SUCCEEDED", successPayload(state.serviceDate(), items, nextPageToken),
        ActorContext.system("appointments"));
  }

  private static Map<String, Object> successPayload(LocalDate date,
      List<Map<String, Object>> items, String nextPageToken) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("snapshotGeneratedAt", NOW.toString());
    data.put("serviceDate", date.toString());
    data.put("items", items);
    if (nextPageToken != null) data.put("nextPageToken", nextPageToken);
    return Map.of("status", "SUCCEEDED", "data", data);
  }

  private static IncomingResult rejected(AppointmentQrRecoveryState state, String code) {
    return result(state, "REJECTED",
        Map.of("status", "REJECTED", "error", Map.of("code", code)),
        ActorContext.system("appointments"));
  }

  private static IncomingResult result(AppointmentQrRecoveryState state, String status,
      Map<String, Object> payload, ActorContext actor) {
    return new IncomingResult(UUID.randomUUID(), AppointmentQrRecoveryService.RESULT_TYPE, 1,
        MessageKind.RESULT, "appointments", NOW, state.recoveryJobId(),
        state.currentQueryMessageId(), state.recoveryJobId(), null, null, null, actor, status,
        null, null, null, null, payload);
  }
}
