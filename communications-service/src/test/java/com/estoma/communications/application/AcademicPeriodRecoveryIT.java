package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.application.port.AcademicPeriodProjectionRepository;
import com.estoma.communications.application.projection.AcademicPeriodRecoveryCoordinator;
import com.estoma.communications.application.projection.AcademicPeriodRetentionPolicy;
import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.consumed.AcademicPeriodActivatedPayload;
import com.estoma.communications.contracts.consumed.AcademicPeriodClosedPayload;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.infrastructure.messaging.EnvelopeValidator;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import com.estoma.communications.testsupport.Envelopes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class AcademicPeriodRecoveryIT extends AbstractPostgresIT {

  @Autowired private ProjectionDispatcher dispatcher;
  @Autowired private AcademicPeriodRecoveryCoordinator recovery;
  @Autowired private AcademicPeriodProjectionRepository periods;
  @Autowired private AcademicPeriodRetentionPolicy retentionPolicy;
  @Autowired private EnvelopeValidator validator;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void resetAcademicPeriodProjection() {
    jdbc.sql("DELETE FROM academic_period_projections").update();
    jdbc.sql("""
        UPDATE academic_period_projection_state
        SET readiness = 'BOOTSTRAPPING', active_academic_period_id = NULL, active_source_version = NULL,
            active_source_occurred_at = NULL, recovery_query_message_id = NULL, recovery_requested_at = NULL, updated_at = now()
        WHERE state_key = 1
        """).update();
    jdbc.sql("DELETE FROM outbox_messages WHERE message_type = 'GetActiveAcademicPeriodSnapshot'").update();
  }

  @Test
  void durableRecoveryQueryBuildsAnAuthoritativePeriodAndUsesTheConfiguredBoundary() {
    recovery.requestBootstrap();
    UUID queryId = latestRecoveryQueryId();
    UUID periodId = UUID.randomUUID();
    MessageEnvelope result = foundResult(queryId, periodId, 7, LocalDate.of(2030, 8, 1), LocalDate.of(2030, 12, 31));

    validator.validateAcademicPeriodRecoveryResult(result);
    assertThat(recovery.handleResult(result)).isTrue();

    assertThat(periods.findAuthoritativeActive()).hasValueSatisfying(period -> {
      assertThat(period.academicPeriodId()).isEqualTo(periodId);
      assertThat(period.sourceVersion()).isEqualTo(7);
    });
    assertThat(retentionPolicy.resolveWashInboxExpiresAt()).isEqualTo(Instant.parse("2031-01-01T06:00:00Z"));
    assertThat(jdbc.sql("""
        SELECT count(*) FROM outbox_messages
        WHERE message_id = :id AND message_kind = 'QUERY' AND destination = 'academic'
        """).param("id", queryId).query(Long.class).single()).isEqualTo(1L);
  }

  @Test
  void staleRecoveryResultCannotReplaceTheCurrentFencedQuery() {
    recovery.requestBootstrap();
    UUID periodId = UUID.randomUUID();
    MessageEnvelope stale = foundResult(UUID.randomUUID(), periodId, 1, LocalDate.of(2030, 1, 1), LocalDate.of(2030, 6, 30));

    validator.validateAcademicPeriodRecoveryResult(stale);
    assertThat(recovery.handleResult(stale)).isFalse();
    assertThat(periods.findAuthoritativeActive()).isEmpty();
  }

  @Test
  void activationCommittedAfterANotFoundSnapshotIsNotRejectedByItsEarlierCommandTimestamp() {
    recovery.requestBootstrap();
    UUID queryId = latestRecoveryQueryId();
    Instant snapshotTime = Instant.parse("2030-08-01T12:00:00Z");

    assertThat(recovery.handleResult(notFoundResult(queryId, snapshotTime))).isTrue();
    UUID periodId = UUID.randomUUID();
    dispatcher.dispatch(activatedEvent(
        periodId, Instant.parse("2030-08-01T11:59:00Z"), LocalDate.of(2030, 8, 1), LocalDate.of(2030, 12, 31)));

    assertThat(periods.findAuthoritativeActive()).hasValueSatisfying(period ->
        assertThat(period.academicPeriodId()).isEqualTo(periodId));
  }

  @Test
  void competingActivePeriodEventsTriggerAFencedOwnerRecoveryInsteadOfChoosingByTimestamp() {
    UUID firstPeriodId = UUID.randomUUID();
    UUID secondPeriodId = UUID.randomUUID();
    UUID currentPeriodId = UUID.randomUUID();
    dispatcher.dispatch(activatedEvent(
        firstPeriodId, Instant.parse("2030-08-01T12:00:00Z"), LocalDate.of(2030, 1, 1), LocalDate.of(2030, 6, 30)));

    dispatcher.dispatch(activatedEvent(
        secondPeriodId, Instant.parse("2030-08-01T12:00:00Z"), LocalDate.of(2030, 8, 1), LocalDate.of(2030, 12, 31)));
    UUID staleQueryId = latestRecoveryQueryId();
    dispatcher.dispatch(activatedEvent(
        currentPeriodId, Instant.parse("2030-08-01T12:01:00Z"), LocalDate.of(2031, 1, 1), LocalDate.of(2031, 6, 30)));

    assertThat(periods.readiness()).isEqualTo("BOOTSTRAPPING");
    UUID queryId = latestRecoveryQueryId();
    assertThat(queryId).isNotEqualTo(staleQueryId);
    assertThat(recovery.handleResult(foundResult(
        staleQueryId, secondPeriodId, 1, LocalDate.of(2030, 8, 1), LocalDate.of(2030, 12, 31)))).isFalse();
    assertThat(recovery.handleResult(foundResult(
        queryId, currentPeriodId, 1, LocalDate.of(2031, 1, 1), LocalDate.of(2031, 6, 30)))).isTrue();
    assertThat(periods.findAuthoritativeActive()).hasValueSatisfying(period ->
        assertThat(period.academicPeriodId()).isEqualTo(currentPeriodId));
  }

  @Test
  void sourceVersionPreventsAStaleActivationFromReopeningAClosedPeriod() {
    UUID periodId = UUID.randomUUID();
    dispatcher.dispatch(Envelopes.event(
        ConsumedEventTypes.ACADEMIC_PERIOD_ACTIVATED,
        new AcademicPeriodActivatedPayload(periodId, "2030-A", "ACTIVE", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 6, 30)),
        periodId,
        1));
    dispatcher.dispatch(Envelopes.event(
        ConsumedEventTypes.ACADEMIC_PERIOD_CLOSED,
        new AcademicPeriodClosedPayload(periodId, "CLOSED"),
        periodId,
        2));
    dispatcher.dispatch(Envelopes.event(
        ConsumedEventTypes.ACADEMIC_PERIOD_ACTIVATED,
        new AcademicPeriodActivatedPayload(periodId, "2030-A", "ACTIVE", LocalDate.of(2030, 1, 1), LocalDate.of(2030, 6, 30)),
        periodId,
        1));

    assertThat(periods.findAuthoritativeActive()).isEmpty();
  }

  private UUID latestRecoveryQueryId() {
    return jdbc.sql("""
        SELECT message_id FROM outbox_messages
        WHERE message_type = 'GetActiveAcademicPeriodSnapshot' AND message_kind = 'QUERY'
        ORDER BY occurred_at DESC, message_id DESC LIMIT 1
        """).query(UUID.class).single();
  }

  private static MessageEnvelope foundResult(
      UUID causationId, UUID periodId, int version, LocalDate startDate, LocalDate endDate) {
    ObjectMapper mapper = new ObjectMapper();
    Instant now = Instant.parse("2030-08-01T12:00:00Z");
    var payload = mapper.createObjectNode().put("status", "FOUND");
    payload.putObject("data")
        .put("academicPeriodId", periodId.toString())
        .put("code", "2030-B")
        .put("status", "ACTIVE")
        .put("startDate", startDate.toString())
        .put("endDate", endDate.toString())
        .put("sourceVersion", version)
        .put("snapshotGeneratedAt", now.toString());
    return new MessageEnvelope(
        UUID.randomUUID(), "GetActiveAcademicPeriodSnapshotResult", 1, MessageKind.RESULT, "academic", now,
        causationId, causationId, UUID.randomUUID(), null, periodId, version, Actor.system("communications"),
        payload);
  }

  private static MessageEnvelope notFoundResult(UUID causationId, Instant occurredAt) {
    ObjectMapper mapper = new ObjectMapper();
    return new MessageEnvelope(
        UUID.randomUUID(), "GetActiveAcademicPeriodSnapshotResult", 1, MessageKind.RESULT, "academic", occurredAt,
        causationId, causationId, UUID.randomUUID(), null, null, null, Actor.system("communications"),
        mapper.createObjectNode().put("status", "NOT_FOUND"));
  }

  private static MessageEnvelope activatedEvent(
      UUID periodId, Instant occurredAt, LocalDate startDate, LocalDate endDate) {
    ObjectMapper mapper = new ObjectMapper();
    return new MessageEnvelope(
        UUID.randomUUID(), ConsumedEventTypes.ACADEMIC_PERIOD_ACTIVATED, 1, MessageKind.EVENT, "academic", occurredAt,
        UUID.randomUUID(), null, null, null, periodId, 1, Actor.system("academic"),
        mapper.valueToTree(new AcademicPeriodActivatedPayload(periodId, "2030-B", "ACTIVE", startDate, endDate)));
  }
}
