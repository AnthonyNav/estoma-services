package com.estoma.washexecution.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washexecution.application.port.WashExecutionRepository;
import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.WashExecution;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.outbound.OutboxRelay;
import com.estoma.washexecution.infrastructure.persistence.jdbc.JdbcTemporal;
import com.estoma.washexecution.testsupport.AbstractMessagingIT;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Opt-in operational experiment; never targets a shared database or broker. */
@EnabledIfSystemProperty(named = "wash.capacity.contention", matches = "true")
class CapacityDecisionContentionIT extends AbstractMessagingIT {
  @Autowired EnvelopeJsonCodec codec;
  @Autowired OutboxRelay relay;
  @Autowired WashExecutionRepository executions;
  private final UUID supervisor = UUID.randomUUID();
  private final UUID slot = UUID.randomUUID();
  private final UUID cabin = UUID.randomUUID();
  private final List<Map<String, Object>> evidence = new ArrayList<>();
  private static final List<String> ROUTES = List.of(
      MessagingNames.COMMAND_RETRY_1, MessagingNames.COMMAND_RETRY_2, MessagingNames.COMMAND_DLQ,
      MessagingNames.EVENT_RETRY_1, MessagingNames.EVENT_RETRY_2, MessagingNames.EVENT_DLQ);

  @DynamicPropertySource
  static void smallPool(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.hikari.maximum-pool-size", () -> "2");
    registry.add("spring.datasource.hikari.minimum-idle", () -> "0");
    registry.add("management.tracing.enabled", () -> "false");
    registry.add("otel.sdk.disabled", () -> "true");
  }

  @Test
  void measureMixedBurstTransientWriterAndExhaustedRetries() throws Exception {
    seed();
    for (String route : ROUTES) declareTap(tap(route), MessagingNames.DLX_EXCHANGE, route);
    try {
      // Criteria fixed before running: bounded mixed/transient runs must drain within
      // 45s with no lost/duplicate mutation and no DLQ; forced exhaustion must route
      // exactly once to DLQ without a domain mutation, then allow safe redelivery.
      evidence.add(batch("mixed-burst", 40, 40, 0, false));
      evidence.add(batch("transient-writer", 20, 20, 1000, false));
      evidence.add(batch("no-compatible-capacity", 40, 40, 0, false, true));
      evidence.add(batch("forced-exhaustion", 1, 5, 0, true));
    } finally {
      Path report = Path.of("target", "capacity-contention-evidence.json");
      Files.createDirectories(report.getParent());
      Files.writeString(report, codec.toNode(Map.of(
          "scope", "ephemeral-owner-postgres-rabbitmq; not shared-cluster or BFF E2E",
          "poolSize", 2, "initialConsumersPerQueue", 2, "maxConsumersPerQueue", 4, "prefetch", 10,
          "retryDelayMs", List.of(5000, 30000), "maxAttempts", 3,
          "scenarios", evidence)).toPrettyString());
    }
    for (Map<String, Object> result : evidence) {
      if (!result.get("scenario").equals("forced-exhaustion")) {
        assertThat(result.get("commandDlq")).as(result.get("scenario").toString()).isEqualTo(0);
        assertThat(result.get("eventDlq")).isEqualTo(0);
      }
    }
  }

  private Map<String, Object> batch(String name, int commandCount, int eventCount,
      long holdMillis, boolean exhaust) throws Exception {
    return batch(name, commandCount, eventCount, holdMillis, exhaust, false);
  }

  private Map<String, Object> batch(String name, int commandCount, int eventCount,
      long holdMillis, boolean exhaust, boolean shouldCancel) throws Exception {
    int cancellationsBefore = jdbc.queryForObject(
        "SELECT count(*) FROM outbox_messages WHERE message_type='WashExecutionCancelled'", Integer.class);
    AppointmentType type = shouldCancel ? AppointmentType.IMMUNOCOMPROMISED : AppointmentType.NORMAL;
    List<MessageEnvelope> commands = new ArrayList<>();
    List<UUID> executionIds = new ArrayList<>();
    for (int i = 0; i < commandCount; i++) {
      UUID appointment = UUID.randomUUID();
      Instant now = Instant.now();
      jdbc.update("""
          INSERT INTO appointment_projection(appointment_id,student_account_id,appointment_time_slot_id,
            course_section_id,appointment_type,instrument_count,piece_type,appointment_status,source_version,updated_at)
          VALUES(?,?,?,?,?,1,'INSTRUMENT','IN_PROGRESS',1,now())
          """, appointment, UUID.randomUUID(), slot, UUID.randomUUID(), type.name());
      WashExecution execution = WashExecution.register(UUID.randomUUID(), appointment,
          type, now.minusSeconds(30), now.minusSeconds(29));
      executions.save(execution, 0);
      execution.start(now.minusSeconds(20));
      executions.save(execution, 1);
      execution.loseResource();
      executions.save(execution, 2);
      executionIds.add(execution.washExecutionId());
      commands.add(new MessageEnvelope(UUID.randomUUID(), "CancelWashExecutionByClinic", 1,
          MessageKind.COMMAND, "platform-bff", now, UUID.randomUUID(), null, UUID.randomUUID(),
          UUID.randomUUID().toString(), null, null, new Actor("ACCOUNT", supervisor, null),
          codec.toNode(Map.of("washExecutionId", execution.washExecutionId(),
              "cancellationSubreason", "CAPACITY_LOSS", "cancelledByAccountId", supervisor,
              "cancelledAt", now, "expectedVersion", 3))));
    }
    List<MessageEnvelope> events = new ArrayList<>();
    for (int i = 0; i < eventCount; i++) {
      UUID id = UUID.randomUUID();
      events.add(new MessageEnvelope(UUID.randomUUID(), "CabinRegistered", 1, MessageKind.EVENT,
          "wash-administration", Instant.now(), UUID.randomUUID(), null, null, null, id, 1,
          new Actor("SYSTEM", null, "wash-administration"), codec.toNode(Map.of(
              "cabinId", id, "code", "E2E-" + id, "name", "Synthetic contention cabin",
              "cabinType", "NORMAL", "status", "ACTIVE"))));
    }
    Map<UUID, Long> commandStarts = new HashMap<>();
    Map<UUID, Long> eventStarts = new HashMap<>();
    Map<UUID, Long> commandTimes = new HashMap<>();
    Map<UUID, Long> eventTimes = new HashMap<>();
    List<Long> readTimes = new ArrayList<>();
    Map<String, Integer> routed = new HashMap<>();
    ROUTES.forEach(route -> routed.put(route, 0));
    Connection blocker = DriverManager.getConnection(POSTGRES.getJdbcUrl(),
        POSTGRES.getUsername(), POSTGRES.getPassword());
    try (blocker; var producer = Executors.newSingleThreadExecutor()) {
      blocker.setAutoCommit(false);
      if (holdMillis > 0 || exhaust) {
        try (var statement = blocker.prepareStatement("SELECT cabin_id FROM cabin_projection WHERE cabin_id=? FOR UPDATE")) {
          statement.setObject(1, cabin);
          statement.executeQuery().close();
        }
      }
      long started = System.nanoTime();
      var publishedEvents = producer.submit(() -> {
        for (MessageEnvelope event : events) send(MessagingNames.EVENTS_EXCHANGE,
            "wash-administration.cabin.registered", event);
      });
      events.forEach(event -> eventStarts.put(event.messageId(), started));
      for (MessageEnvelope command : commands) {
        commandStarts.put(command.messageId(), System.nanoTime());
        send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, command);
      }
      publishedEvents.get();
      boolean released = holdMillis == 0 && !exhaust;
      if (released) blocker.rollback();
      long deadline = started + 45_000_000_000L;
      while (System.nanoTime() < deadline) {
        if (!released && !exhaust && millisSince(started) >= holdMillis) {
          blocker.rollback();
          released = true;
        }
        long readAt = System.nanoTime();
        jdbc.queryForObject("SELECT count(*) FROM cabin_projection", Integer.class);
        readTimes.add(millisSince(readAt));
        relay.relay();
        Message message;
        while ((message = rabbit.receive(BFF_RESULT_QUEUE)) != null) {
          var result = codec.decode(message.getBody());
          if (commandStarts.containsKey(result.causationId())) {
            assertThat(result.messageType()).isEqualTo(shouldCancel
                ? "CancelWashExecutionByClinicSucceeded" : "CancelWashExecutionByClinicRejected");
            if (!shouldCancel) assertThat(result.payload().path("error").path("code").asText())
                .isEqualTo("CAPACITY_ALTERNATIVE_AVAILABLE");
            assertThat(commandTimes).doesNotContainKey(result.causationId());
            commandTimes.put(result.causationId(), millisSince(commandStarts.get(result.causationId())));
          }
        }
        for (UUID id : jdbc.queryForList("SELECT message_id FROM processed_messages WHERE status='APPLIED'", UUID.class)) {
          if (eventStarts.containsKey(id)) eventTimes.putIfAbsent(id, millisSince(eventStarts.get(id)));
        }
        for (String route : ROUTES) {
          while ((message = rabbit.receive(tap(route))) != null) {
            routed.compute(route, (key, value) -> value + 1);
            if (route.equals(MessagingNames.COMMAND_DLQ)) {
              UUID id = codec.decode(message.getBody()).messageId();
              commandTimes.put(id, millisSince(commandStarts.get(id)));
            }
          }
        }
        if (commandTimes.size() == commandCount && eventTimes.size() == eventCount) break;
        Thread.sleep(25);
      }
      blocker.rollback();
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("scenario", name);
      result.put("expectedDecision", shouldCancel ? "CANCELLED" : "REJECTED_ALTERNATIVE_AVAILABLE");
      result.put("commands", commandCount);
      result.put("events", eventCount);
      result.put("commandTerminalCount", commandTimes.size());
      result.put("eventAppliedCount", eventTimes.size());
      result.put("elapsedMs", millisSince(started));
      result.put("commandLatencyMs", percentiles(new ArrayList<>(commandTimes.values())));
      result.put("projectionObservedLatencyMs", percentiles(new ArrayList<>(eventTimes.values())));
      result.put("ordinaryReadLatencyMs", percentiles(readTimes));
      result.put("routes", routed);
      result.put("commandDlq", routed.get(MessagingNames.COMMAND_DLQ));
      result.put("eventDlq", routed.get(MessagingNames.EVENT_DLQ));
      System.out.println("CAPACITY_CONTENTION " + codec.toNode(result));
      assertThat(commandTimes).hasSize(commandCount);
      assertThat(eventTimes).hasSize(eventCount);
      for (UUID id : executionIds) {
        var execution = executions.findById(id).orElseThrow();
        assertThat(execution.version()).isEqualTo(shouldCancel ? 4 : 3);
        assertThat(execution.status().name()).isEqualTo(shouldCancel ? "CANCELLED" : "PENDING_REASSIGNMENT");
      }
      assertThat(jdbc.queryForObject("SELECT count(*) FROM resource_assignments", Integer.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM cleaning_periods", Integer.class)).isZero();
      assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_messages WHERE message_type='WashExecutionCancelled'", Integer.class))
          .isEqualTo(cancellationsBefore + (shouldCancel ? commandCount : 0));
      if (shouldCancel) {
        for (UUID id : executionIds) assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM outbox_messages WHERE message_type='WashExecutionCancelled' AND aggregate_id=?",
            Integer.class, id)).isEqualTo(1);
        for (int i = 0; i < commandCount; i++) {
          Message event = rabbit.receive(EVENT_TAP_QUEUE, 1000);
          assertThat(event).isNotNull();
          assertThat(codec.decode(event.getBody()).messageType()).isEqualTo("WashExecutionCancelled");
        }
        assertThat(rabbit.receive(EVENT_TAP_QUEUE)).isNull();
      }
      List<Message> deadCommands = new ArrayList<>();
      for (int i = 0; i < routed.get(MessagingNames.COMMAND_DLQ); i++) {
        Message dead = rabbit.receive(MessagingNames.COMMAND_DLQ, 1000);
        assertThat(dead).isNotNull();
        assertThat(commandStarts).containsKey(codec.decode(dead.getBody()).messageId());
        deadCommands.add(dead);
      }
      if (exhaust) {
        assertThat(routed.get(MessagingNames.COMMAND_RETRY_1)).isEqualTo(1);
        assertThat(routed.get(MessagingNames.COMMAND_RETRY_2)).isEqualTo(1);
        assertThat(routed.get(MessagingNames.COMMAND_DLQ)).isEqualTo(1);
        Message dead = deadCommands.getFirst();
        assertThat(codec.decode(dead.getBody()).messageId()).isEqualTo(commands.getFirst().messageId());
        // Controlled redrive only inside this disposable broker after releasing the writer.
        rabbit.send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID, dead);
        long recoveryDeadline = System.nanoTime() + 5_000_000_000L;
        Message recovered = null;
        while (System.nanoTime() < recoveryDeadline && recovered == null) {
          relay.relay();
          recovered = rabbit.receive(BFF_RESULT_QUEUE, 50);
        }
        assertThat(recovered).isNotNull();
        MessageEnvelope recoveredResult = codec.decode(recovered.getBody());
        assertThat(recoveredResult.messageType()).isEqualTo("CancelWashExecutionByClinicRejected");
        assertThat(recoveredResult.causationId()).isEqualTo(commands.getFirst().messageId());
        assertThat(recoveredResult.payload().path("error").path("code").asText())
            .isEqualTo("CAPACITY_ALTERNATIVE_AVAILABLE");
        var unchanged = executions.findById(executionIds.getFirst()).orElseThrow();
        assertThat(unchanged.status().name()).isEqualTo("PENDING_REASSIGNMENT");
        assertThat(unchanged.version()).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM outbox_messages WHERE message_type='WashExecutionCancelled' AND aggregate_id=?",
            Integer.class, executionIds.getFirst())).isZero();
        result.put("controlledRedrive", "REJECTED_WITHOUT_CANCELLATION");
      }
      return result;
    }
  }

  private void seed() {
    for (String projection : List.of("appointments", "authorization", "wash-administration")) {
      jdbc.update("""
          INSERT INTO projection_recovery_state(projection_name,source_logical_component_id,
            readiness_status,last_successful_rebuild_at,updated_at) VALUES(?,?,'READY',now(),now())
          """, projection, projection);
    }
    jdbc.update("INSERT INTO authorization_role_projection VALUES(?,'SUPERVISOR_LAVADO',1,now())", supervisor);
    jdbc.update("INSERT INTO authorization_access_projection VALUES(?,?,'LAVADO_ULTRASONICO','ACTIVE',1,now())",
        supervisor, UUID.randomUUID());
    Instant now = Instant.now();
    jdbc.update("""
        INSERT INTO appointment_time_slot_projection(appointment_time_slot_id,calendar_id,service_date,
          starts_at,ends_at,timezone,arrival_tolerance_until,status,source_version,updated_at)
        VALUES(?,?,?,?,?,'UTC',?,'ACTIVE',1,now())
        """, slot, UUID.randomUUID(), now.atZone(ZoneOffset.UTC).toLocalDate(),
        JdbcTemporal.timestamp(now.minusSeconds(60)), JdbcTemporal.timestamp(now.plusSeconds(3600)),
        JdbcTemporal.timestamp(now.plusSeconds(600)));
    jdbc.update("INSERT INTO cabin_projection VALUES(?,'E2E-BASE','Synthetic cabin','NORMAL','ACTIVE',1,now())", cabin);
    jdbc.update("INSERT INTO tank_projection VALUES(?,?,'E2E-TANK','Synthetic tank',1000,'ACTIVE',1,now())", UUID.randomUUID(), cabin);
  }

  private void send(String exchange, String key, MessageEnvelope envelope) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setMessageId(envelope.messageId().toString());
    rabbit.send(exchange, key, new Message(codec.encode(envelope), props));
  }

  private static String tap(String route) { return "test.capacity.observe." + route; }
  private static long millisSince(long nanos) { return (System.nanoTime() - nanos) / 1_000_000; }
  private static Map<String, Long> percentiles(List<Long> values) {
    values.sort(Long::compareTo);
    if (values.isEmpty()) return Map.of();
    return Map.of("p50", values.get((values.size()-1)/2),
        "p95", values.get((int)Math.ceil(values.size()*0.95)-1), "max", values.getLast());
  }
}
