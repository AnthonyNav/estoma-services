package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.application.command.DeactivatePersonCommand;
import com.estoma.users.application.query.ResolvePersonByInstitutionalEmailQuery;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.query.ResolveOutcome;
import com.estoma.users.contract.query.ResolvePersonByInstitutionalEmailData;
import com.estoma.users.contract.result.ResultPayload;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.testsupport.AbstractMessagingIT;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * The full happy/edge paths for {@code ResolvePersonByInstitutionalEmail}
 * (queries.md §2), end to end: a real Query envelope published to {@code
 * platform.queries}, consumed by {@link
 * com.estoma.users.messaging.inbound.QueryListener}, resolved, and
 * relayed back out as a correlated {@code
 * ResolvePersonByInstitutionalEmailResult} — proving the whole wire
 * contract, not just {@link PersonQueryDispatcher} in isolation (see
 * {@code PersonQueryDispatcherTest} for that).
 *
 * <p>Fixture Persons are created/deactivated via the real {@link
 * PersonCommandDispatcher} directly (bypassing the broker, like {@code
 * PersonStatusHistoryTransactionIT}), draining their "bff"-routed
 * Results before each test's own Query runs — otherwise those rows would
 * sit PENDING in the Postgres container shared by every {@code *IT} class
 * in this JVM and could be claimed by an unrelated test's own {@code
 * claimBatch} (docs/system-contract.md §8).
 */
class ResolvePersonByInstitutionalEmailIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private PersonCommandDispatcher dispatcher;
  @Autowired private JdbcClient jdbc;

  @Test
  void anActivePersonResolvesToFound() {
    UUID personId = UUID.randomUUID();
    String email = "ada." + personId + "@example.edu";
    createPerson(personId, email);

    ResolvePersonByInstitutionalEmailData data = resolve(email);

    assertThat(data.outcome()).isEqualTo(ResolveOutcome.FOUND);
    assertThat(data.personId()).isEqualTo(personId);
    assertThat(data.status()).isEqualTo("ACTIVE");
  }

  @Test
  void anInactivePersonResolvesToInactiveMatch() {
    UUID personId = UUID.randomUUID();
    String email = "ada." + personId + "@example.edu";
    createPerson(personId, email);
    deactivatePerson(personId);

    ResolvePersonByInstitutionalEmailData data = resolve(email);

    assertThat(data.outcome()).isEqualTo(ResolveOutcome.INACTIVE_MATCH);
    assertThat(data.personId()).isEqualTo(personId);
    assertThat(data.status()).isEqualTo("INACTIVE");
  }

  @Test
  void anUnknownEmailResolvesToNotFound() {
    ResolvePersonByInstitutionalEmailData data = resolve("nobody." + UUID.randomUUID() + "@example.edu");

    assertThat(data.outcome()).isEqualTo(ResolveOutcome.NOT_FOUND);
    assertThat(data.personId()).isNull();
    assertThat(data.status()).isNull();
  }

  @Test
  void redeliveryOfTheSameQueryReEvaluatesTheLookup() {
    // No Consumer Inbox for Query (queries.md §2): the exact same
    // envelope bytes published twice must resolve twice, independently,
    // rather than being deduplicated the way a Command redelivery is.
    UUID personId = UUID.randomUUID();
    String email = "ada." + personId + "@example.edu";
    createPerson(personId, email);

    Queue resultsQueue = declareResultsQueue("academic");
    Queue eventsQueue = amqpAdmin.declareQueue();
    amqpAdmin.declareBinding(
        BindingBuilder.bind(eventsQueue).to(new org.springframework.amqp.core.TopicExchange(AmqpNames.EXCHANGE_EVENTS)).with("identity.person.*"));
    try {
      UUID messageId = UUID.randomUUID();
      UUID operationId = UUID.randomUUID();
      UUID correlationId = UUID.randomUUID();
      MessageEnvelope query = queryEnvelope(messageId, operationId, correlationId, "academic", email, new Actor.System("academic"));
      byte[] body = codec.encode(query);

      publishQuery(body);
      publishQuery(body);

      MessageEnvelope firstResult = codec.decode(awaitMessage(resultsQueue, correlationId).getBody());
      MessageEnvelope secondResult = codec.decode(awaitMessage(resultsQueue, correlationId).getBody());
      for (MessageEnvelope result : java.util.List.of(firstResult, secondResult)) {
        assertThat(result.causationId()).isEqualTo(messageId);
        assertThat(result.operationId()).isEqualTo(operationId);
        assertThat(result.correlationId()).isEqualTo(correlationId);
      }
      assertThat(
              jdbc.sql("SELECT count(*) FROM processed_messages WHERE message_id = :messageId")
                  .param("messageId", messageId)
                  .query(Long.class)
                  .single())
          .isZero();
      assertThat(rabbitTemplate.receive(eventsQueue.getName())).isNull();
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
      amqpAdmin.deleteQueue(eventsQueue.getName());
    }
  }

  @Test
  void aMalformedEmailGoesStraightToTheDlq() {
    publishQuery("academic", "not-an-email", UUID.randomUUID());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_QUERIES_DLQ)).isNotNull());
  }

  @Test
  void aProducerOutsideTheMatrixGoesStraightToTheDlq() {
    publishQuery("bff", "juan@universidad.mx", UUID.randomUUID());

    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(() -> assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_QUERIES_DLQ)).isNotNull());
  }

  private ResolvePersonByInstitutionalEmailData resolve(String email) {
    Queue resultsQueue = declareResultsQueue("academic");
    try {
      UUID correlationId = UUID.randomUUID();
      publishQuery("academic", email, correlationId);

      Message resultMessage = awaitMessage(resultsQueue, correlationId);
      MessageEnvelope result = codec.decode(resultMessage.getBody());
      assertThat(result.messageType()).isEqualTo(MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL_RESULT);
      ResultPayload payload = codec.convertPayload(result, ResultPayload.class);
      return codec.readValue(codec.writeValueAsString(payload.data()), ResolvePersonByInstitutionalEmailData.class);
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
    }
  }

  private void createPerson(UUID personId, String email) {
    Queue resultsQueue = declareResultsQueue("bff");
    try {
      UUID correlationId = UUID.randomUUID();
      CreatePersonCommand command = new CreatePersonCommand(personId, "Ada", "Lovelace", null, email, null);
      CommandOutcome outcome = dispatcher.dispatch(commandEnvelope(MessageTypes.CREATE_PERSON, correlationId, personId, codec.toJsonNode(command)));
      assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
      awaitMessage(resultsQueue, correlationId);
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
    }
  }

  private void deactivatePerson(UUID personId) {
    Queue resultsQueue = declareResultsQueue("bff");
    try {
      UUID correlationId = UUID.randomUUID();
      DeactivatePersonCommand command = new DeactivatePersonCommand(personId, 1, "ADMINISTRATIVE_REQUEST", null);
      CommandOutcome outcome =
          dispatcher.dispatch(
              commandEnvelope(MessageTypes.DEACTIVATE_PERSON, correlationId, personId, codec.toJsonNode(command)));
      assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
      awaitMessage(resultsQueue, correlationId);
    } finally {
      amqpAdmin.deleteQueue(resultsQueue.getName());
    }
  }

  private Queue declareResultsQueue(String routingKey) {
    Queue resultsQueue = amqpAdmin.declareQueue();
    amqpAdmin.declareBinding(
        BindingBuilder.bind(resultsQueue).to(new DirectExchange(AmqpNames.EXCHANGE_RESULTS)).with(routingKey));
    return resultsQueue;
  }

  private Message awaitMessage(Queue queue, UUID correlationId) {
    Message[] holder = new Message[1];
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () -> {
              Message message = rabbitTemplate.receive(queue.getName());
              assertThat(message).isNotNull();
              MessageEnvelope envelope = codec.decode(message.getBody());
              assertThat(envelope.correlationId()).isEqualTo(correlationId);
              holder[0] = message;
            });
    return holder[0];
  }

  private void publishQuery(String producer, String institutionalEmail, UUID correlationId) {
    publishQuery(
        queryEnvelope(
            UUID.randomUUID(),
            UUID.randomUUID(),
            correlationId,
            producer,
            institutionalEmail,
            new Actor.System("academic")));
  }

  private MessageEnvelope queryEnvelope(
      UUID messageId,
      UUID operationId,
      UUID correlationId,
      String producer,
      String institutionalEmail,
      Actor actor) {
    ResolvePersonByInstitutionalEmailQuery query = new ResolvePersonByInstitutionalEmailQuery(institutionalEmail);
    return new MessageEnvelope(
        messageId,
        MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL,
        1,
        MessageKind.QUERY,
        producer,
        Instant.now(),
        correlationId,
        null,
        operationId,
        null,
        null,
        null,
        actor,
        codec.toJsonNode(query));
  }

  private void publishQuery(MessageEnvelope envelope) {
    publishQuery(codec.encode(envelope));
  }

  private void publishQuery(byte[] body) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    rabbitTemplate.send(AmqpNames.EXCHANGE_QUERIES, AmqpNames.ROUTING_KEY_QUERIES, new Message(body, props));
  }

  private MessageEnvelope commandEnvelope(String messageType, UUID correlationId, UUID aggregateId, JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        messageType,
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.now(),
        correlationId,
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        aggregateId,
        null,
        new Actor.Account(UUID.randomUUID()),
        payload);
  }
}
