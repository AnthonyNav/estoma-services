package com.estoma.washadministration.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationRoleProjectionRepository;
import com.estoma.washadministration.contracts.Actor;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessageKind;
import com.estoma.washadministration.contracts.MessagingNames;
import com.estoma.washadministration.contracts.command.CreateServiceCalendarCommand;
import com.estoma.washadministration.contracts.consumed.AccountRoleAssignedPayload;
import com.estoma.washadministration.contracts.consumed.SystemAccessGrantedPayload;
import com.estoma.washadministration.contracts.query.GetWashAdministrationConfigurationRequest;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washadministration.infrastructure.messaging.outbound.OutboxRelay;
import com.estoma.washadministration.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.simple.JdbcClient;

class MessagingIntegrationIT extends AbstractPostgresIT {

  private static final UUID ACCOUNT_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000021");
  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired
  @Qualifier("requiredRouteRabbitTemplate")
  private RabbitTemplate rabbit;

  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private OutboxRelay relay;
  @Autowired private JdbcClient jdbc;
  @Autowired private AuthorizationRoleProjectionRepository roles;
  @Autowired private AuthorizationAccessProjectionRepository accesses;

  private final List<String> temporaryQueues = new ArrayList<>();

  @BeforeEach
  void prepareBroker() {
    List.of(
            MessagingNames.COMMAND_QUEUE,
            MessagingNames.QUERY_QUEUE,
            MessagingNames.EVENTS_QUEUE,
            MessagingNames.RESULTS_QUEUE,
            MessagingNames.COMMAND_DLQ,
            MessagingNames.QUERY_DLQ,
            MessagingNames.EVENTS_DLQ,
            MessagingNames.RESULTS_DLQ)
        .forEach(this::purge);
  }

  @AfterEach
  void deleteTemporaryQueues() {
    temporaryQueues.forEach(
        queue -> rabbit.execute(channel -> {
          channel.queueDelete(queue);
          return null;
        }));
    temporaryQueues.clear();
  }

  @Test
  void commandProducesResultAndEventThroughOutboxRelay() {
    roles.upsert(ACCOUNT_ID, "ADMINISTRADOR_LAVADO", 1, NOW);
    accesses.upsert(ACCOUNT_ID, "LAVADO_ULTRASONICO", "ACTIVE", 1, NOW);

    String resultQueue = declareQueue(MessagingNames.RESULTS_EXCHANGE, "bff");
    String eventQueue =
        declareQueue(MessagingNames.EVENTS_EXCHANGE, "wash-administration.service-calendar.created");
    UUID correlationId = UUID.randomUUID();
    MessageEnvelope command =
        envelope(
            UUID.randomUUID(),
            "CreateServiceCalendar",
            MessageKind.COMMAND,
            "bff",
            correlationId,
            UUID.randomUUID(),
            "command-" + UUID.randomUUID(),
            Actor.account(ACCOUNT_ID),
            new CreateServiceCalendarCommand(
                UUID.randomUUID(),
                "MSG-CALENDAR",
                "Messaging calendar",
                "UTC",
                LocalDate.of(2026, 1, 1),
                null),
            null,
            null);

    send(MessagingNames.COMMANDS_EXCHANGE, MessagingNames.COMMAND_ROUTING_KEY, command);
    awaitPending(correlationId, 2);
    relay.relay();

    MessageEnvelope result = codec.decode(receive(resultQueue).getBody());
    MessageEnvelope event = codec.decode(receive(eventQueue).getBody());
    assertThat(result.payload().get("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(event.messageType()).isEqualTo("ServiceCalendarCreated");
    assertThat(event.aggregateVersion()).isEqualTo(1);
  }

  @Test
  void queryProducesSucceededAndUnauthorizedQueryProducesRejectedResult() {
    String appointmentsQueue = declareQueue(MessagingNames.RESULTS_EXCHANGE, "appointments");
    UUID authorizedCorrelation = UUID.randomUUID();
    MessageEnvelope authorizedQuery =
        envelope(
            UUID.randomUUID(),
            "GetWashAdministrationConfiguration",
            MessageKind.QUERY,
            "appointments",
            authorizedCorrelation,
            null,
            null,
            Actor.system("appointments"),
            new GetWashAdministrationConfigurationRequest(null),
            null,
            null);

    send(MessagingNames.QUERIES_EXCHANGE, MessagingNames.QUERY_ROUTING_KEY, authorizedQuery);
    awaitPending(authorizedCorrelation, 1);
    relay.relay();
    assertThat(codec.decode(receive(appointmentsQueue).getBody()).payload().get("status").asText())
        .isEqualTo("SUCCEEDED");

    String bffQueue = declareQueue(MessagingNames.RESULTS_EXCHANGE, "bff");
    UUID rejectedCorrelation = UUID.randomUUID();
    MessageEnvelope unauthorizedQuery =
        envelope(
            UUID.randomUUID(),
            "GetWashAdministrationConfiguration",
            MessageKind.QUERY,
            "bff",
            rejectedCorrelation,
            null,
            null,
            Actor.system("bff"),
            new GetWashAdministrationConfigurationRequest(null),
            null,
            null);

    send(MessagingNames.QUERIES_EXCHANGE, MessagingNames.QUERY_ROUTING_KEY, unauthorizedQuery);
    awaitPending(rejectedCorrelation, 1);
    relay.relay();
    assertThat(codec.decode(receive(bffQueue).getBody()).payload().get("status").asText())
        .isEqualTo("REJECTED");
  }

  @Test
  void authorizationEventUpdatesProjectionOnceAndMalformedQueryGoesToDlq() {
    String routingKey = "authorization.account-role.assigned";
    MessageEnvelope event =
        envelope(
            UUID.randomUUID(),
            "AccountRoleAssigned",
            MessageKind.EVENT,
            "authorization",
            UUID.randomUUID(),
            null,
            null,
            Actor.system("authorization"),
            new AccountRoleAssignedPayload(
                ACCOUNT_ID, UUID.randomUUID(), "ADMINISTRADOR_LAVADO", NOW),
            ACCOUNT_ID,
            7);

    send(MessagingNames.EVENTS_EXCHANGE, routingKey, event);
    await(() -> roles.findByAccountId(ACCOUNT_ID).map(value -> value.sourceVersion() == 7).orElse(false));

    MessageEnvelope duplicate =
        new MessageEnvelope(
            event.messageId(),
            event.messageType(),
            event.messageVersion(),
            event.messageKind(),
            event.producer(),
            event.occurredAt(),
            event.correlationId(),
            event.causationId(),
            event.operationId(),
            event.idempotencyKey(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.actor(),
            codec.toJsonNode(
                new AccountRoleAssignedPayload(
                    ACCOUNT_ID, UUID.randomUUID(), "OTHER_ROLE", NOW)));
    send(MessagingNames.EVENTS_EXCHANGE, routingKey, duplicate);
    await(() -> roles.findByAccountId(ACCOUNT_ID).map(value -> "ADMINISTRADOR_LAVADO".equals(value.roleCode())).orElse(false));

    MessageEnvelope malformed =
        envelope(
            UUID.randomUUID(),
            "SystemAccessGranted",
            MessageKind.EVENT,
            "authorization",
            UUID.randomUUID(),
            null,
            null,
            Actor.system("authorization"),
            new SystemAccessGrantedPayload(
                UUID.randomUUID(), ACCOUNT_ID, UUID.randomUUID(), null, UUID.randomUUID(), "ROLE", "ACTIVE", NOW),
            UUID.randomUUID(),
            1);
    send(MessagingNames.EVENTS_EXCHANGE, "authorization.system-access.granted", malformed);
    assertThat(receive(MessagingNames.EVENTS_DLQ).getBody()).isEqualTo(codec.encode(malformed));

    sendRaw(MessagingNames.QUERIES_EXCHANGE, MessagingNames.QUERY_ROUTING_KEY, new byte[] {'{', '}'});
    Message dlq = receive(MessagingNames.QUERY_DLQ);
    assertThat(dlq.getBody()).containsExactly('{', '}');
  }

  private MessageEnvelope envelope(
      UUID messageId,
      String messageType,
      MessageKind kind,
      String producer,
      UUID correlationId,
      UUID operationId,
      String idempotencyKey,
      Actor actor,
      Object payload,
      UUID aggregateId,
      Integer aggregateVersion) {
    return new MessageEnvelope(
        messageId,
        messageType,
        1,
        kind,
        producer,
        NOW,
        correlationId,
        null,
        operationId,
        idempotencyKey,
        aggregateId,
        aggregateVersion,
        actor,
        codec.toJsonNode(payload));
  }

  private void send(String exchange, String routingKey, MessageEnvelope envelope) {
    sendRaw(exchange, routingKey, codec.encode(envelope));
  }

  private void sendRaw(String exchange, String routingKey, byte[] body) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbit.send(exchange, routingKey, new Message(body, properties));
  }

  private String declareQueue(String exchange, String routingKey) {
    String queue = "test.wash-administration." + UUID.randomUUID();
    rabbit.execute(
        channel -> {
          channel.queueDeclare(queue, false, false, true, null);
          channel.queueBind(queue, exchange, routingKey);
          return null;
        });
    temporaryQueues.add(queue);
    return queue;
  }

  private void purge(String queue) {
    rabbit.execute(
        channel -> {
          channel.queuePurge(queue);
          return null;
        });
  }

  private Message receive(String queue) {
    Message message = rabbit.receive(queue, 5_000);
    assertThat(message).as("message in %s", queue).isNotNull();
    return message;
  }

  private long pendingFor(UUID correlationId) {
    return jdbc.sql(
            "SELECT count(*) FROM outbox_messages WHERE correlation_id = :correlationId AND status = 'PENDING'")
        .param("correlationId", correlationId)
        .query(Long.class)
        .single();
  }

  private void awaitPending(UUID correlationId, long expected) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (pendingFor(correlationId) != expected && System.nanoTime() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting", exception);
      }
    }
    Message dlq = rabbit.receive(MessagingNames.QUERY_DLQ, 100);
    assertThat(pendingFor(correlationId))
        .as(
            "pending outbox rows for %s; current states: %s; query dlq: %s",
            correlationId,
            statesFor(correlationId),
            dlq == null ? "none" : new String(dlq.getBody()))
        .isEqualTo(expected);
  }

  private String statesFor(UUID correlationId) {
    return jdbc.sql(
            "SELECT coalesce(string_agg(status::text, ','), 'NONE') FROM outbox_messages WHERE correlation_id = :correlationId")
        .param("correlationId", correlationId)
        .query(String.class)
        .single();
  }

  private static void await(BooleanSupplier condition) {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
      try {
        Thread.sleep(50);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new AssertionError("interrupted while waiting", exception);
      }
    }
    assertThat(condition.getAsBoolean()).isTrue();
  }
}
