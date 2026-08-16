package com.estoma.washadministration.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washadministration.application.command.ActorContext;
import com.estoma.washadministration.application.command.AdministrativeCommand;
import com.estoma.washadministration.application.command.CommandMetadata;
import com.estoma.washadministration.application.command.CommandOutcome;
import com.estoma.washadministration.application.command.CommandRequest;
import com.estoma.washadministration.application.command.WashAdministrationCommandDispatcher;
import com.estoma.washadministration.application.port.ProjectionRecoveryJobRepository;
import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessageKind;
import com.estoma.washadministration.infrastructure.messaging.AuthorizationRecoveryHealthIndicator;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washadministration.infrastructure.messaging.inbound.AuthorizationRecoveryResultHandler;
import com.estoma.washadministration.infrastructure.messaging.inbound.CommandMessageMapper;
import com.estoma.washadministration.infrastructure.messaging.inbound.InvalidMessageException;
import com.estoma.washadministration.infrastructure.messaging.outbound.AuthorizationProjectionRecoveryCoordinator;
import com.estoma.washadministration.infrastructure.messaging.outbound.OutboundWirePayloadMapper;
import com.estoma.washadministration.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;

class Pr3HardeningIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private WashAdministrationCommandDispatcher dispatcher;
  @Autowired private ProjectionRecoveryJobRepository recoveryJobs;
  @Autowired private AuthorizationProjectionRecoveryCoordinator recoveryCoordinator;
  @Autowired private AuthorizationRecoveryResultHandler recoveryResults;
  @Autowired private AuthorizationRecoveryHealthIndicator recoveryHealth;
  @Autowired private CommandMessageMapper commandMapper;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private OutboundWirePayloadMapper wirePayloads;

  @Test
  void missingAuthorizationProjectionSchedulesEntityRecoveryAfterRejectedCommandRollback() {
    UUID accountId = UUID.randomUUID();
    UUID calendarId = UUID.randomUUID();
    CommandRequest request =
        new CommandRequest(
            new CommandMetadata(
                UUID.randomUUID(),
                "CreateServiceCalendar",
                1,
                "bff",
                NOW,
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                "recovery-" + UUID.randomUUID(),
                null,
                null,
                ActorContext.account(accountId)),
            new AdministrativeCommand.CreateServiceCalendar(
                calendarId,
                "RECOVERY",
                "Recovery",
                "UTC",
                LocalDate.of(2026, 1, 1),
                null));

    CommandOutcome outcome = dispatcher.dispatch(request);

    assertThat(outcome.status()).isEqualTo(CommandOutcome.Status.REJECTED);
    assertThat(outcome.errorCode()).isEqualTo("ACTOR_NOT_AUTHORIZED");
    assertThat(recoveryJobs.hasActiveEntityRecovery(accountId)).isTrue();
    assertThat(jdbc.sql("SELECT count(*) FROM service_calendars WHERE service_calendar_id = :id")
        .param("id", calendarId).query(Long.class).single()).isZero();
  }

  @Test
  void uninitializedProjectionBootstrapsFullAndBecomesReadyAfterCorrelatedResult() {
    clearAuthorizationRecoveryHistory();
    assertThat(recoveryHealth.health().getStatus()).isEqualTo(Status.DOWN);

    assertThat(recoveryCoordinator.ensureFullRecoveryInitialized()).isTrue();
    assertThat(recoveryJobs.hasActiveFullRecovery()).isTrue();
    assertThat(recoveryCoordinator.processNext()).isTrue();

    QueryAttempt query = jdbc.sql(
            """
            SELECT message_id, message_type, operation_id, correlation_id
            FROM outbox_messages
            WHERE message_kind = 'QUERY'
            ORDER BY occurred_at DESC
            LIMIT 1
            """)
        .query((rs, rowNum) -> new QueryAttempt(
            rs.getObject("message_id", UUID.class),
            rs.getString("message_type"),
            rs.getObject("operation_id", UUID.class),
            rs.getObject("correlation_id", UUID.class)))
        .single();

    Map<String, Object> page = new LinkedHashMap<>();
    page.put("items", java.util.List.of());
    page.put("hasMore", false);
    page.put("snapshotGeneratedAt", NOW);
    Map<String, Object> resultPayload = new LinkedHashMap<>();
    resultPayload.put("status", "SUCCEEDED");
    resultPayload.put("data", page);

    recoveryResults.handle(new MessageEnvelope(
        UUID.randomUUID(),
        query.messageType() + "Result",
        1,
        MessageKind.RESULT,
        "authorization",
        NOW,
        query.correlationId(),
        query.messageId(),
        query.operationId(),
        null,
        null,
        null,
        Actor.system("authorization"),
        codec.toJsonNode(resultPayload)));

    assertThat(recoveryJobs.hasCompletedFullRecovery()).isTrue();
    assertThat(recoveryHealth.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void recoveryResultMustMatchOperationAndCorrelation() {
    clearAuthorizationRecoveryHistory();
    recoveryCoordinator.ensureFullRecoveryInitialized();
    recoveryCoordinator.processNext();
    QueryAttempt query = jdbc.sql(
            "SELECT message_id, message_type, operation_id, correlation_id FROM outbox_messages WHERE message_kind = 'QUERY' LIMIT 1")
        .query((rs, rowNum) -> new QueryAttempt(
            rs.getObject("message_id", UUID.class),
            rs.getString("message_type"),
            rs.getObject("operation_id", UUID.class),
            rs.getObject("correlation_id", UUID.class)))
        .single();

    MessageEnvelope wrong = new MessageEnvelope(
        UUID.randomUUID(),
        query.messageType() + "Result",
        1,
        MessageKind.RESULT,
        "authorization",
        NOW,
        UUID.randomUUID(),
        query.messageId(),
        query.operationId(),
        null,
        null,
        null,
        Actor.system("authorization"),
        codec.toJsonNode(Map.of("status", "SUCCEEDED", "data", Map.of("items", java.util.List.of(), "hasMore", false, "snapshotGeneratedAt", NOW))));

    assertThatThrownBy(() -> recoveryResults.handle(wrong))
        .isInstanceOf(InvalidMessageException.class)
        .hasMessage("AUTHORIZATION_RESULT_CORRELATION_MISMATCH");
    assertThat(recoveryJobs.hasActiveFullRecovery()).isTrue();
  }

  @Test
  void commandMissingPrimitiveFieldIsRejectedBeforeApplicationMapping() {
    var payload = codec.toJsonNode(Map.of("tankId", UUID.randomUUID(), "name", "Tank", "configuredCapacity", 2));
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), "UpdateTank", 1, MessageKind.COMMAND, "bff", NOW,
        UUID.randomUUID(), null, UUID.randomUUID(), "key", null, null,
        Actor.account(UUID.randomUUID()), payload);

    assertThatThrownBy(() -> commandMapper.map(envelope))
        .isInstanceOf(InvalidMessageException.class)
        .hasMessage("COMMAND_FIELD_REQUIRED:expectedVersion");
  }

  @Test
  void outboundEventPayloadIsReducedToTheDeclaredWireContract() {
    UUID cabinId = UUID.randomUUID();
    String internal = codec.write(Map.of(
        "cabinId", cabinId,
        "code", "CAB-1",
        "name", "Cabin 1",
        "cabinType", "NORMAL",
        "status", "ACTIVE",
        "version", 4,
        "occurredAt", NOW));

    var wire = wirePayloads.map(MessageKind.EVENT, "CabinActivated", internal);

    assertThat(wire.get("cabinId").asText()).isEqualTo(cabinId.toString());
    assertThat(wire.get("status").asText()).isEqualTo("ACTIVE");
    assertThat(wire.get("version").asInt()).isEqualTo(4);
    assertThat(wire.get("code")).isNull();
    assertThat(wire.get("name")).isNull();
    assertThat(wire.get("cabinType")).isNull();
  }

  private record QueryAttempt(UUID messageId, String messageType, UUID operationId, UUID correlationId) {}
}
