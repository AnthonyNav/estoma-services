package com.estoma.washexecution.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.outbound.OutboxRelay;
import com.estoma.washexecution.infrastructure.persistence.jdbc.JdbcTemporal;
import com.estoma.washexecution.testsupport.AbstractMessagingIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

class CommandMessagingIT extends AbstractMessagingIT {
  private static final Instant START = Instant.parse("2026-08-23T10:00:00Z");

  @Autowired EnvelopeJsonCodec codec;
  @Autowired OutboxRelay relay;

  @Test
  void commandCommitsInboxDomainAndOutboxThenRelayPublishesResultAndSemanticEvent() throws Exception {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    UUID slot = UUID.randomUUID();
    UUID appointment = UUID.randomUUID();
    UUID execution = UUID.randomUUID();
    seedReady("appointments", "appointments");
    seedReady("authorization", "authorization");
    seedSupervisor(supervisor);
    seedSlot(slot);
    seedAppointment(appointment, student, slot);

    MessageEnvelope command = commandEnvelope(
        "RegisterWashArrival",
        supervisor,
        Map.of(
            "washExecutionId", execution,
            "appointmentId", appointment,
            "arrivedAt", START));

    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, command);
    awaitCount("SELECT count(*) FROM processed_messages WHERE consumer_name='wash-execution.commands' AND message_id=? AND status='APPLIED'", command.messageId(), 1);

    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM wash_executions WHERE wash_execution_id=? AND appointment_id=?",
        Integer.class, execution, appointment)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM outbox_messages WHERE status='PENDING'",
        Integer.class)).isEqualTo(2);

    relay.relay();

    Message resultMessage = rabbit.receive(BFF_RESULT_QUEUE, 5_000);
    Message eventMessage = rabbit.receive(EVENT_TAP_QUEUE, 5_000);
    assertThat(resultMessage).isNotNull();
    assertThat(eventMessage).isNotNull();
    MessageEnvelope result = codec.decode(resultMessage.getBody());
    MessageEnvelope event = codec.decode(eventMessage.getBody());
    assertThat(result.messageType()).isEqualTo("RegisterWashArrivalSucceeded");
    assertThat(result.messageKind()).isEqualTo(MessageKind.RESULT);
    assertThat(result.causationId()).isEqualTo(command.messageId());
    assertThat(result.correlationId()).isEqualTo(command.correlationId());
    assertThat(event.messageType()).isEqualTo("WashArrivalRegistered");
    assertThat(event.messageKind()).isEqualTo(MessageKind.EVENT);
    assertThat(event.aggregateId()).isEqualTo(execution);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM outbox_messages WHERE status='PUBLISHED'",
        Integer.class)).isEqualTo(2);

    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, command);
    Thread.sleep(300);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM wash_executions WHERE wash_execution_id=?",
        Integer.class, execution)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM outbox_messages",
        Integer.class)).isEqualTo(2);
  }

  @Test
  void canonicalReassignmentResolvesAndLegacyReplaySharesIdempotencyWithoutDuplicateEffects()
      throws Exception {
    UUID supervisor = UUID.randomUUID();
    UUID student = UUID.randomUUID();
    UUID slot = UUID.randomUUID();
    UUID appointment = UUID.randomUUID();
    UUID execution = UUID.randomUUID();
    seedReady("appointments", "appointments");
    seedReady("authorization", "authorization");
    seedReady("wash-administration", "wash-administration");
    seedSupervisor(supervisor);
    seedSlot(slot);
    seedAppointment(appointment, student, slot);

    MessageEnvelope arrival = commandEnvelope(
        "RegisterWashArrival", supervisor,
        Map.of("washExecutionId", execution, "appointmentId", appointment, "arrivedAt", START));
    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, arrival);
    awaitCount("SELECT count(*) FROM processed_messages WHERE consumer_name='wash-execution.commands' "
        + "AND message_id=? AND status='APPLIED'", arrival.messageId(), 1);

    MessageEnvelope decision = commandEnvelope(
        "DecideWashEntry", supervisor,
        Map.of(
            "washExecutionId", execution,
            "identityConfirmed", true,
            "requirementsSatisfied", true,
            "decision", "AUTHORIZED",
            "decidedByAccountId", supervisor,
            "decidedAt", START.plusSeconds(300),
            "expectedVersion", 1));
    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, decision);
    awaitCount("SELECT count(*) FROM wash_executions WHERE wash_execution_id=? "
        + "AND status='PENDING_REASSIGNMENT' AND version=2", execution, 1);

    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    seedCabin(cabin);
    seedTank(tank, cabin);
    Instant assignedAt = START.plusSeconds(360);

    MessageEnvelope spoofed = reassignmentEnvelope(supervisor,
        canonicalReassignment(execution, cabin, tank, UUID.randomUUID(), assignedAt, 2),
        "resolve-spoofed", execution, 2);
    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, spoofed);
    awaitCount("SELECT count(*) FROM processed_messages WHERE consumer_name='wash-execution.commands' "
        + "AND message_id=? AND status='APPLIED'", spoofed.messageId(), 1);
    assertThat(resultCode(spoofed.messageId())).isEqualTo("ACTOR_NOT_AUTHORIZED");
    assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_assignments", Integer.class)).isZero();

    String equivalentKey = "resolve-equivalent:" + execution;
    MessageEnvelope canonical = reassignmentEnvelope(supervisor,
        canonicalReassignment(execution, cabin, tank, supervisor, assignedAt, 2), equivalentKey);
    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, canonical);
    awaitCount("SELECT count(*) FROM wash_executions WHERE wash_execution_id=? "
        + "AND status='IN_PROGRESS' AND version=3", execution, 1);
    assertThat(resultType(canonical.messageId()))
        .isEqualTo("ResolvePendingReassignmentSucceeded");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM resource_assignments WHERE wash_execution_id=? "
            + "AND cabin_id=? AND tank_id=? AND released_at IS NULL",
        Integer.class, execution, cabin, tank)).isEqualTo(1);

    MessageEnvelope legacyReplay = reassignmentEnvelope(supervisor,
        legacyReassignment(execution, cabin, tank, supervisor, assignedAt, 2), equivalentKey);
    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, legacyReplay);
    awaitCount("SELECT count(*) FROM processed_messages WHERE consumer_name='wash-execution.commands' "
        + "AND message_id=? AND status='APPLIED'", legacyReplay.messageId(), 1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM resource_assignments WHERE wash_execution_id=?",
        Integer.class, execution)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM outbox_messages WHERE message_type='WashResourceAssigned' "
            + "AND aggregate_id=?",
        Integer.class, execution)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM command_idempotency_records WHERE producer='bff' "
            + "AND operation_type='ResolvePendingReassignment' AND idempotency_key=?",
        Integer.class, equivalentKey)).isEqualTo(1);

    MessageEnvelope stale = reassignmentEnvelope(supervisor,
        canonicalReassignment(execution, cabin, tank, supervisor, assignedAt.plusSeconds(1), 2),
        "resolve-stale:" + execution, execution, 2);
    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, stale);
    awaitCount("SELECT count(*) FROM processed_messages WHERE consumer_name='wash-execution.commands' "
        + "AND message_id=? AND status='APPLIED'", stale.messageId(), 1);
    assertThat(resultCode(stale.messageId())).isEqualTo("VERSION_CONFLICT");
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM resource_assignments WHERE wash_execution_id=?",
        Integer.class, execution)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM outbox_messages WHERE message_type='WashResourceAssigned' "
            + "AND aggregate_id=?",
        Integer.class, execution)).isEqualTo(1);
  }

  @Test
  void structurallyInvalidCommandGoesToDlqWithoutDomainMutation() {
    UUID supervisor = UUID.randomUUID();
    MessageEnvelope invalid = new MessageEnvelope(
        UUID.randomUUID(), "RegisterWashArrival", 2, MessageKind.COMMAND, "platform-bff",
        START, UUID.randomUUID(), null, UUID.randomUUID(), "invalid-v2", null, null,
        new Actor("ACCOUNT", supervisor, null),
        codec.toNode(Map.of(
            "washExecutionId", UUID.randomUUID(),
            "appointmentId", UUID.randomUUID(),
            "arrivedAt", START)));

    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, invalid);

    Message dead = rabbit.receive(MessagingNames.COMMAND_DLQ, 5_000);
    assertThat(dead).isNotNull();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM wash_executions", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_messages", Integer.class)).isZero();
  }

  private MessageEnvelope commandEnvelope(String type, UUID actorId, Map<String, Object> payload) {
    return commandEnvelope(type, actorId, payload, UUID.randomUUID().toString());
  }

  private MessageEnvelope commandEnvelope(
      String type, UUID actorId, Map<String, Object> payload, String idempotencyKey) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.COMMAND, "platform-bff", START,
        UUID.randomUUID(), null, UUID.randomUUID(), idempotencyKey, null, null,
        new Actor("ACCOUNT", actorId, null), codec.toNode(payload));
  }

  private MessageEnvelope reassignmentEnvelope(
      UUID actorId, Map<String, Object> payload, String idempotencyKey) {
    return reassignmentEnvelope(actorId, payload, idempotencyKey,
        (UUID) payload.get("washExecutionId"), (Integer) payload.get("expectedVersion"));
  }

  private MessageEnvelope reassignmentEnvelope(
      UUID actorId, Map<String, Object> payload, String idempotencyKey,
      UUID aggregateId, int aggregateVersion) {
    return new MessageEnvelope(
        UUID.randomUUID(), "ResolvePendingReassignment", 1, MessageKind.COMMAND, "bff", START,
        UUID.randomUUID(), null, UUID.randomUUID(), idempotencyKey, aggregateId, aggregateVersion,
        new Actor("ACCOUNT", actorId, null), codec.toNode(payload));
  }

  private Map<String, Object> canonicalReassignment(
      UUID execution, UUID cabin, UUID tank, UUID supervisor, Instant assignedAt,
      int expectedVersion) {
    return Map.of(
        "washExecutionId", execution,
        "selectedCabinId", cabin,
        "selectedTankId", tank,
        "assignedByAccountId", supervisor,
        "assignedAt", assignedAt,
        "expectedVersion", expectedVersion);
  }

  private Map<String, Object> legacyReassignment(
      UUID execution, UUID cabin, UUID tank, UUID supervisor, Instant assignedAt,
      int expectedVersion) {
    return Map.of(
        "washExecutionId", execution,
        "cabinId", cabin,
        "tankId", tank,
        "resolvedByAccountId", supervisor,
        "resolvedAt", assignedAt,
        "expectedVersion", expectedVersion);
  }

  private void send(String exchange, String routingKey, MessageEnvelope envelope) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(envelope.messageId().toString());
    rabbit.send(exchange, routingKey, new Message(codec.encode(envelope), props));
  }

  private void seedReady(String projection, String source) {
    jdbc.update("""
        INSERT INTO projection_recovery_state(
          projection_name,source_logical_component_id,readiness_status,last_successful_rebuild_at,last_error,updated_at)
        VALUES(?,?,'READY',now(),NULL,now())
        """, projection, source);
  }

  private void seedSupervisor(UUID accountId) {
    jdbc.update("""
        INSERT INTO authorization_role_projection(account_id,role_code,role_source_version,updated_at)
        VALUES(?,'SUPERVISOR_LAVADO',1,now())
        """, accountId);
    jdbc.update("""
        INSERT INTO authorization_access_projection(
          account_id,system_id,system_code,access_status,access_source_version,updated_at)
        VALUES(?,?,'LAVADO_ULTRASONICO','ACTIVE',1,now())
        """, accountId, UUID.randomUUID());
  }

  private void seedSlot(UUID slotId) {
    jdbc.update("""
        INSERT INTO appointment_time_slot_projection(
          appointment_time_slot_id,calendar_id,service_date,starts_at,ends_at,timezone,
          arrival_tolerance_until,status,source_version,updated_at)
        VALUES(?,?,?, ?,?,'UTC',?,'ACTIVE',1,now())
        """, slotId, UUID.randomUUID(), LocalDate.of(2026, 8, 23),
        JdbcTemporal.timestamp(START.plusSeconds(15 * 60L)),
        JdbcTemporal.timestamp(START.plusSeconds(75 * 60L)),
        JdbcTemporal.timestamp(START.plusSeconds(25 * 60L)));
  }

  private void seedAppointment(UUID appointmentId, UUID student, UUID slotId) {
    jdbc.update("""
        INSERT INTO appointment_projection(
          appointment_id,student_account_id,appointment_time_slot_id,course_section_id,
          appointment_type,instrument_count,piece_type,appointment_status,entry_authorized_at,
          source_version,updated_at)
        VALUES(?,?,?,?, 'NORMAL',1,'INSTRUMENT','SCHEDULED',NULL,1,now())
        """, appointmentId, student, slotId, UUID.randomUUID());
  }

  private void seedCabin(UUID cabinId) {
    jdbc.update("""
        INSERT INTO cabin_projection(cabin_id,code,name,cabin_type,status,source_version,updated_at)
        VALUES(?,'C-WIRE','Canonical wire cabin','NORMAL','ACTIVE',1,now())
        """, cabinId);
  }

  private void seedTank(UUID tankId, UUID cabinId) {
    jdbc.update("""
        INSERT INTO tank_projection(
          tank_id,cabin_id,code,name,configured_capacity,status,source_version,updated_at)
        VALUES(?,?,'T-WIRE','Canonical wire tank',2,'ACTIVE',1,now())
        """, tankId, cabinId);
  }

  private String resultCode(UUID commandMessageId) {
    return jdbc.queryForObject(
        "SELECT payload_json->'error'->>'code' FROM outbox_messages WHERE causation_id=? "
            + "AND message_kind='RESULT'",
        String.class, commandMessageId);
  }

  private String resultType(UUID commandMessageId) {
    return jdbc.queryForObject(
        "SELECT message_type FROM outbox_messages WHERE causation_id=? AND message_kind='RESULT'",
        String.class, commandMessageId);
  }

  private void awaitCount(String sql, Object key, int expected) throws InterruptedException {
    long deadline = System.nanoTime() + 5_000_000_000L;
    while (System.nanoTime() < deadline) {
      Integer count = jdbc.queryForObject(sql, Integer.class, key);
      if (count != null && count == expected) return;
      Thread.sleep(50);
    }
    throw new AssertionError("Timed out waiting for DB condition");
  }
}
