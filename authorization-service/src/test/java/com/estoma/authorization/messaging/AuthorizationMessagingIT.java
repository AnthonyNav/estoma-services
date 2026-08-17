package com.estoma.authorization.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.MessagingNames;
import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.domain.model.AccountRoleAssignment;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authorization.infrastructure.messaging.outbound.OutboxRelay;
import com.estoma.authorization.testsupport.AbstractMessagingIT;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AuthorizationMessagingIT extends AbstractMessagingIT {

  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
  private static final UUID SUPERADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000205");

  @Autowired private RabbitTemplate rabbit;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private ObjectMapper mapper;
  @Autowired private OutboxRelay relay;
  @Autowired private ProjectionRecoveryCoordinator recovery;
  @Autowired private PersonStatusProjectionRepository persons;
  @Autowired private AccountStatusProjectionRepository accounts;
  @Autowired private AccountRoleRepository accountRoles;
  @Autowired private JdbcClient jdbc;

  private String temporaryQueue;

  @AfterEach
  void deleteTemporaryQueue() {
    if (temporaryQueue != null) {
      amqpAdmin.deleteQueue(temporaryQueue);
    }
  }

  @Test
  void commandCommitsThenRelaysResultAndEvent() {
    UUID actor = seedSuperadmin();
    UUID target = seedAccount(LoginType.USERNAME);
    UUID commandId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String idempotencyKey = UUID.randomUUID().toString();
    String resultQueue = declareDirectResultQueue("bff");
    String eventQueue = declareEventQueue("authorization.account-role.assigned");

    send(new MessageEnvelope(
        commandId,
        "AssignAccountRole",
        1,
        MessageKind.COMMAND,
        "bff",
        NOW,
        correlationId,
        null,
        operationId,
        idempotencyKey,
        null,
        null,
        new WireActor("ACCOUNT", actor, null),
        mapper.createObjectNode()
            .put("accountId", target.toString())
            .put("roleCode", "ADMINISTRADOR_PRACTICAS")),
        MessagingNames.COMMANDS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
        assertThat(accountRoles.findByAccountId(target)).isPresent());
    relay.relay();

    JsonNode result = receive(resultQueue);
    assertThat(result.path("messageType").asText()).isEqualTo("AssignAccountRoleResult");
    assertThat(result.path("causationId").asText()).isEqualTo(commandId.toString());
    assertThat(result.path("operationId").asText()).isEqualTo(operationId.toString());
    assertThat(result.path("idempotencyKey").asText()).isEqualTo(idempotencyKey);
    assertThat(result.path("payload").path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(result.path("payload").has("error")).isFalse();

    JsonNode event = receive(eventQueue);
    assertThat(event.path("messageType").asText()).isEqualTo("AccountRoleAssigned");
    assertThat(event.path("aggregateId").asText()).isEqualTo(target.toString());
    assertThat(event.path("payload").path("roleCode").asText()).isEqualTo("ADMINISTRADOR_PRACTICAS");
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(
        jdbc.sql("SELECT count(*) FROM outbox_messages WHERE status = 'PUBLISHED'")
            .query(Long.class)
            .single()).isEqualTo(2));
  }

  @Test
  void queryResultIsDurablyRecordedBeforeRelay() {
    UUID accountId = seedAccount(LoginType.USERNAME);
    accountRoles.insert(AccountRoleAssignment.createNew(
        accountId,
        SUPERADMIN_ROLE_ID,
        RoleCode.SUPERADMIN,
        ActorRef.system("authorization"),
        NOW));
    UUID queryId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    String resultQueue = declareDirectResultQueue("bff");

    send(new MessageEnvelope(
        queryId,
        "GetAccountAuthorizationSnapshot",
        1,
        MessageKind.QUERY,
        "bff",
        NOW,
        UUID.randomUUID(),
        null,
        operationId,
        null,
        null,
        null,
        new WireActor("SYSTEM", null, "bff"),
        mapper.createObjectNode().put("accountId", accountId.toString())),
        MessagingNames.QUERIES_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
        jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'GetAccountAuthorizationSnapshotResult'")
            .query(Long.class)
            .single()).isEqualTo(1));
    relay.relay();

    JsonNode result = receive(resultQueue);
    assertThat(result.path("messageType").asText()).isEqualTo("GetAccountAuthorizationSnapshotResult");
    assertThat(result.path("causationId").asText()).isEqualTo(queryId.toString());
    assertThat(result.path("operationId").asText()).isEqualTo(operationId.toString());
    assertThat(result.path("payload").path("status").asText()).isEqualTo("FOUND");
    assertThat(result.path("payload").path("data").path("accountId").asText()).isEqualTo(accountId.toString());
  }

  @Test
  void externalEventAndCorrelatedRecoveryResultApplyThroughBroker() {
    UUID personId = UUID.randomUUID();
    send(new MessageEnvelope(
        UUID.randomUUID(),
        "PersonCreated",
        1,
        MessageKind.EVENT,
        "users",
        NOW,
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        personId,
        1,
        new WireActor("SYSTEM", null, "users"),
        mapper.createObjectNode()
            .put("personId", personId.toString())
            .put("firstName", "Ada")
            .put("paternalSurname", "Lovelace")
            .put("maternalSurname", "Byron")
            .put("institutionalEmail", "ada@estoma.mx")
            .put("phone", "5550000000")
            .put("status", "ACTIVE")
            .put("version", 1)
            .put("createdAt", NOW.toString())),
        MessagingNames.EVENTS_EXCHANGE,
        "users.person.created");
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(persons.findByPersonId(personId)).isPresent());

    UUID accountId = UUID.randomUUID();
    String ownerQueue = declareDirectQueue(MessagingNames.QUERIES_EXCHANGE, "authentication");
    recovery.requestAccount(accountId);
    relay.relay();
    JsonNode request = receive(ownerQueue);
    UUID queryId = UUID.fromString(request.path("messageId").asText());

    send(new MessageEnvelope(
        UUID.randomUUID(),
        "GetAccountStatusSnapshotResult",
        1,
        MessageKind.RESULT,
        "authentication",
        NOW,
        UUID.fromString(request.path("correlationId").asText()),
        queryId,
        UUID.fromString(request.path("operationId").asText()),
        null,
        null,
        null,
        new WireActor("SYSTEM", null, "authentication"),
        mapper.createObjectNode()
            .put("status", "FOUND")
            .set("data", mapper.createObjectNode()
                .put("accountId", accountId.toString())
                .put("personId", personId.toString())
                .put("loginType", "USERNAME")
                .put("status", "ACTIVE")
                .put("sourceVersion", 1)
                .put("updatedAt", NOW.toString()))),
        MessagingNames.RESULTS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(accounts.findByAccountId(accountId)).isPresent());
  }

  @Test
  void invalidWireMessageIsConfirmedIntoTheCommandDlq() {
    send(new MessageEnvelope(
        UUID.randomUUID(),
        "AssignAccountRole",
        2,
        MessageKind.COMMAND,
        "bff",
        NOW,
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new WireActor("ACCOUNT", UUID.randomUUID(), null),
        mapper.createObjectNode()
            .put("accountId", UUID.randomUUID().toString())
            .put("roleCode", "ALUMNO")),
        MessagingNames.COMMANDS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);

    JsonNode dlq = receive(MessagingNames.COMMAND_DLQ);
    assertThat(dlq.path("messageVersion").asInt()).isEqualTo(2);
  }

  private UUID seedSuperadmin() {
    UUID accountId = seedAccount(LoginType.USERNAME);
    accountRoles.insert(AccountRoleAssignment.createNew(
        accountId, SUPERADMIN_ROLE_ID, RoleCode.SUPERADMIN, ActorRef.system("authorization"), NOW));
    return accountId;
  }

  private UUID seedAccount(LoginType loginType) {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    persons.upsertIfNewer(new PersonStatusProjectionRepository.PersonStatusProjection(
        personId, ProjectedStatus.ACTIVE, 1, NOW));
    accounts.upsertIfNewer(new AccountStatusProjectionRepository.AccountStatusProjection(
        accountId, personId, loginType, ProjectedStatus.ACTIVE, 1, NOW));
    return accountId;
  }

  private String declareDirectResultQueue(String routingKey) {
    return declareDirectQueue(MessagingNames.RESULTS_EXCHANGE, routingKey);
  }

  private String declareDirectQueue(String exchangeName, String routingKey) {
    temporaryQueue = "authorization-it-" + UUID.randomUUID();
    Queue queue = new Queue(temporaryQueue, false, true, true);
    amqpAdmin.declareQueue(queue);
    amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new DirectExchange(exchangeName)).with(routingKey));
    return temporaryQueue;
  }

  private String declareEventQueue(String routingKey) {
    temporaryQueue = "authorization-it-" + UUID.randomUUID();
    Queue queue = new Queue(temporaryQueue, false, true, true);
    amqpAdmin.declareQueue(queue);
    amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(MessagingNames.EVENTS_EXCHANGE)).with(routingKey));
    return temporaryQueue;
  }

  private void send(MessageEnvelope envelope, String exchange, String routingKey) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setMessageId(envelope.messageId().toString());
    rabbit.send(exchange, routingKey, new Message(codec.encode(envelope), properties));
  }

  private JsonNode receive(String queue) {
    Message message = rabbit.receive(queue, 10_000);
    assertThat(message).as("message from %s", queue).isNotNull();
    return codec.readTree(message.getBody());
  }
}
