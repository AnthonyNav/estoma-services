package com.estoma.authorization.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.authorization.application.port.AccountRoleRepository;
import com.estoma.authorization.application.port.AccountStatusProjectionRepository;
import com.estoma.authorization.application.port.PersonStatusProjectionRepository;
import com.estoma.authorization.application.port.ProjectionRecoveryJobRepository;
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
import java.util.ArrayList;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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
  private static final UUID ALUMNO_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000201");
  private static final UUID SUPERADMIN_ROLE_ID = UUID.fromString("00000000-0000-0000-0000-000000000205");

  @Autowired private RabbitTemplate rabbit;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private ObjectMapper mapper;
  @Autowired private OutboxRelay relay;
  @Autowired private ProjectionRecoveryCoordinator recovery;
  @Autowired private PersonStatusProjectionRepository persons;
  @Autowired private AccountStatusProjectionRepository accounts;
  @Autowired private AccountRoleRepository accountRoles;
  @Autowired private ProjectionRecoveryJobRepository recoveryJobs;
  @Autowired private JdbcClient jdbc;

  private final List<String> temporaryQueues = new ArrayList<>();

  @AfterEach
  void deleteTemporaryQueue() {
    temporaryQueues.forEach(amqpAdmin::deleteQueue);
    temporaryQueues.clear();
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
  void rejectedCommandPublishesOnlyARejectedResult() {
    UUID actor = seedSuperadmin();
    String resultQueue = declareDirectResultQueue("bff");
    String eventQueue = declareEventQueue("authorization.#");

    send(assignRoleCommand(
        UUID.randomUUID(), actor, actor, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString()),
        MessagingNames.COMMANDS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
        jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'RESULT'")
            .query(Long.class)
            .single()).isEqualTo(1));
    relay.relay();

    JsonNode result = receive(resultQueue);
    assertThat(result.path("payload").path("status").asText()).isEqualTo("REJECTED");
    assertThat(result.path("payload").has("data")).isFalse();
    assertThat(result.path("payload").path("error").path("details").isObject()).isTrue();
    assertThat(rabbit.receive(eventQueue, 250)).isNull();
    assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_kind = 'EVENT'")
        .query(Long.class)
        .single()).isZero();
  }

  @Test
  void duplicateBrokerDeliveryCommitsOneMutationEventAndResult() {
    UUID actor = seedSuperadmin();
    UUID target = seedAccount(LoginType.USERNAME);
    String resultQueue = declareDirectResultQueue("bff");
    String eventQueue = declareEventQueue("authorization.account-role.assigned");
    MessageEnvelope command = assignRoleCommand(
        UUID.randomUUID(), actor, target, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString());

    send(command, MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID);
    send(command, MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID);

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      assertThat(accountRoles.findByAccountId(target)).isPresent();
      assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages")
          .query(Long.class)
          .single()).isEqualTo(2);
    });
    relay.relay();

    assertThat(receive(resultQueue).path("payload").path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(receive(eventQueue).path("messageType").asText()).isEqualTo("AccountRoleAssigned");
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
    var job = recovery.requestAccount(accountId);
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

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      assertThat(accounts.findByAccountId(accountId)).isPresent();
      assertThat(recoveryJobs.findById(job.recoveryJobId())).get()
          .extracting(ProjectionRecoveryJobRepository.RecoveryJob::status)
          .isEqualTo("COMPLETED");
    });
  }

  @Test
  void lateRecoveryResultLeavesTheCompletedProjectionUntouched() {
    UUID personId = UUID.randomUUID();
    UUID accountId = UUID.randomUUID();
    String ownerQueue = declareDirectQueue(MessagingNames.QUERIES_EXCHANGE, "authentication");
    var job = recovery.requestAccount(accountId);
    relay.relay();
    JsonNode request = receive(ownerQueue);

    send(accountSnapshotFound(request, accountId, personId),
        MessagingNames.RESULTS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
        recoveryJobs.findById(job.recoveryJobId())).get()
        .extracting(ProjectionRecoveryJobRepository.RecoveryJob::status)
        .isEqualTo("COMPLETED"));

    send(accountSnapshotNotFound(request),
        MessagingNames.RESULTS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);
    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(
        jdbc.sql("SELECT count(*) FROM processed_messages WHERE consumer_name = 'authorization-recovery-results'")
            .query(Long.class)
            .single()).isEqualTo(2));

    assertThat(accounts.findByAccountId(accountId)).get()
        .extracting(AccountStatusProjectionRepository.AccountStatusProjection::sourceVersion)
        .isEqualTo(1);
    assertThat(recoveryJobs.findById(job.recoveryJobId())).get()
        .extracting(ProjectionRecoveryJobRepository.RecoveryJob::status)
        .isEqualTo("COMPLETED");
  }

  @Test
  void duplicateEligibilityEventGrantsPracticesAccessExactlyOnce() {
    UUID accountId = seedAccount(LoginType.MATRICULA);
    accountRoles.insert(AccountRoleAssignment.createNew(
        accountId, ALUMNO_ROLE_ID, RoleCode.ALUMNO, ActorRef.system("academic"), NOW));
    UUID eligibilityId = UUID.randomUUID();
    MessageEnvelope event = eligibilityEvent(UUID.randomUUID(), eligibilityId, UUID.randomUUID(), accountId);
    String eventQueue = declareEventQueue("authorization.system-access.granted");

    send(event, MessagingNames.EVENTS_EXCHANGE, "academic.student-eligibility.changed");
    send(event, MessagingNames.EVENTS_EXCHANGE, "academic.student-eligibility.changed");

    await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
      assertThat(jdbc.sql("SELECT count(*) FROM system_accesses WHERE account_id = :accountId")
          .param("accountId", accountId)
          .query(Long.class)
          .single()).isEqualTo(1);
      assertThat(jdbc.sql("SELECT count(*) FROM outbox_messages WHERE message_type = 'SystemAccessGranted'")
          .query(Long.class)
          .single()).isEqualTo(1);
    });
    relay.relay();

    JsonNode granted = receive(eventQueue);
    assertThat(granted.path("messageType").asText()).isEqualTo("SystemAccessGranted");
    assertThat(granted.path("payload").path("accountId").asText()).isEqualTo(accountId.toString());
    assertThat(granted.path("payload").path("systemCode").asText()).isEqualTo("PRACTICAS_PROFESIONALES");
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
    String queueName = "authorization-it-" + UUID.randomUUID();
    temporaryQueues.add(queueName);
    Queue queue = new Queue(queueName, false, true, true);
    amqpAdmin.declareQueue(queue);
    amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new DirectExchange(exchangeName)).with(routingKey));
    return queueName;
  }

  private String declareEventQueue(String routingKey) {
    String queueName = "authorization-it-" + UUID.randomUUID();
    temporaryQueues.add(queueName);
    Queue queue = new Queue(queueName, false, true, true);
    amqpAdmin.declareQueue(queue);
    amqpAdmin.declareBinding(BindingBuilder.bind(queue).to(new TopicExchange(MessagingNames.EVENTS_EXCHANGE)).with(routingKey));
    return queueName;
  }

  private MessageEnvelope assignRoleCommand(
      UUID messageId,
      UUID actorId,
      UUID accountId,
      UUID correlationId,
      UUID operationId,
      String idempotencyKey) {
    return new MessageEnvelope(
        messageId,
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
        new WireActor("ACCOUNT", actorId, null),
        mapper.createObjectNode()
            .put("accountId", accountId.toString())
            .put("roleCode", "ADMINISTRADOR_PRACTICAS"));
  }

  private MessageEnvelope accountSnapshotFound(JsonNode request, UUID accountId, UUID personId) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        "GetAccountStatusSnapshotResult",
        1,
        MessageKind.RESULT,
        "authentication",
        NOW,
        UUID.fromString(request.path("correlationId").asText()),
        UUID.fromString(request.path("messageId").asText()),
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
                .put("updatedAt", NOW.toString())));
  }

  private MessageEnvelope accountSnapshotNotFound(JsonNode request) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        "GetAccountStatusSnapshotResult",
        1,
        MessageKind.RESULT,
        "authentication",
        NOW,
        UUID.fromString(request.path("correlationId").asText()),
        UUID.fromString(request.path("messageId").asText()),
        UUID.fromString(request.path("operationId").asText()),
        null,
        null,
        null,
        new WireActor("SYSTEM", null, "authentication"),
        mapper.createObjectNode().put("status", "NOT_FOUND"));
  }

  private MessageEnvelope eligibilityEvent(
      UUID messageId, UUID eligibilityId, UUID studentAcademicProfileId, UUID accountId) {
    return new MessageEnvelope(
        messageId,
        "StudentAcademicEligibilityChanged",
        1,
        MessageKind.EVENT,
        "academic",
        NOW,
        UUID.randomUUID(),
        UUID.randomUUID(),
        null,
        null,
        eligibilityId,
        1,
        new WireActor("SYSTEM", null, "academic"),
        mapper.createObjectNode()
            .put("eligibilityId", eligibilityId.toString())
            .put("studentAcademicProfileId", studentAcademicProfileId.toString())
            .put("accountId", accountId.toString())
            .put("systemCode", "PRACTICAS_PROFESIONALES")
            .put("eligible", true)
            .put("policyVersion", 1)
            .put("eligibilityVersion", 1)
            .put("academicProfileVersion", 1)
            .put("evaluatedAt", NOW.toString()));
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
