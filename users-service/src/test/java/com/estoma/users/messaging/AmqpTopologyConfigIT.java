package com.estoma.users.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.estoma.users.testsupport.AbstractMessagingIT;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the real broker topology {@link AmqpTopologyConfig} declares
 * (docs/system-contract.md §9), against an actual RabbitMQ instance — not
 * just that the {@code @Bean} methods run without throwing.
 *
 * <p>Since the command handlers/dispatcher commit, {@code CommandListener}
 * is a live consumer of {@code commands.users-service}: any message
 * landing there is immediately picked up, not left sitting for a test to
 * {@code receive()} directly. Two tests below observe the routing proof
 * one hop further downstream instead (the DLQ), since a raw non-JSON test
 * payload is exactly what {@code CommandListener} rejects as an invalid
 * message and forwards there — which still proves the binding delivered
 * it to {@code commands.users-service} in the first place.
 */
class AmqpTopologyConfigIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private TopicExchange eventsExchange;

  @Test
  void commandPublishedToCommandsExchangeIsConsumedAndAnInvalidPayloadReachesTheDlq() {
    String body = "test-" + UUID.randomUUID();

    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, body);

    // CommandListener consumes it from commands.users-service and, since
    // a bare string isn't a valid envelope, routes it to the DLQ --
    // proving platform.commands really is bound to commands.users-service
    // with routing key "users-service".
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNotNull());
  }

  @Test
  void messagePublishedWithTheDlqRoutingKeyArrivesOnTheDlq() {
    String body = "test-" + UUID.randomUUID();

    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_DLQ, body);

    Message received = rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ, 2_000);
    assertThat(received).isNotNull();
  }

  @Test
  void messageRoutedToTheFiveSecondRetryTierReappearsOnCommandsAndThenReachesTheDlq() {
    MessageProperties props = new MessageProperties();
    props.getHeaders().put(AmqpNames.HEADER_ATTEMPT, 1);
    Message message = new Message("retry-me".getBytes(), props);

    rabbitTemplate.send(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_RETRY_5S, message);

    // Once the 5s TTL elapses, the broker dead-letters it back onto
    // platform.commands with the fixed command routing key, landing on
    // commands.users-service -- proving the whole retry-tier chain (TTL +
    // dead-letter-exchange + dead-letter-routing-key) is wired correctly.
    // CommandListener immediately consumes it there and, since "retry-me"
    // isn't a valid envelope, forwards it to the DLQ; that's where this
    // test observes it, one hop further downstream than in the commit 7
    // version of this test (see class Javadoc).
    await()
        .atMost(Duration.ofSeconds(8))
        .untilAsserted(
            () -> {
              Message redelivered = rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ);
              assertThat(redelivered).isNotNull();
              assertThat(new String(redelivered.getBody())).isEqualTo("retry-me");
            });
  }

  @Test
  void eventPublishedWithAMatchingRoutingKeyReachesAQueueBoundWithAWildcard() {
    Queue testQueue = amqpAdmin.declareQueue(); // anonymous, auto-delete
    Binding binding = BindingBuilder.bind(testQueue).to(eventsExchange).with("identity.person.*");
    amqpAdmin.declareBinding(binding);
    try {
      rabbitTemplate.convertAndSend(
          AmqpNames.EXCHANGE_EVENTS, AmqpNames.ROUTING_KEY_PERSON_CREATED, "person-created-test");

      Message received = rabbitTemplate.receive(testQueue.getName(), 2_000);
      assertThat(received).isNotNull();
    } finally {
      amqpAdmin.deleteQueue(testQueue.getName());
    }
  }

  @Test
  void queryPublishedToQueriesExchangeIsConsumedAndAnInvalidPayloadReachesItsOwnDlq() {
    String body = "test-" + UUID.randomUUID();

    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_QUERIES, AmqpNames.ROUTING_KEY_QUERIES, body);

    // QueryListener consumes it from queries.users-service and, since a
    // bare string isn't a valid envelope, routes it to the QUERY DLQ --
    // proving platform.queries really is bound to queries.users-service
    // with routing key "users-service", and that the query queue's own
    // DLQ is a separate destination from the command queue's.
    await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_QUERIES_DLQ)).isNotNull());
    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNull();
  }

  @Test
  void messagePublishedWithTheQueriesDlqRoutingKeyArrivesOnTheQueriesDlq() {
    String body = "test-" + UUID.randomUUID();

    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_QUERIES_DLQ, body);

    Message received = rabbitTemplate.receive(AmqpNames.QUEUE_QUERIES_DLQ, 2_000);
    assertThat(received).isNotNull();
  }

  @Test
  void queryRoutedToTheFiveSecondRetryTierReappearsOnQueriesAndThenReachesItsOwnDlq() {
    MessageProperties props = new MessageProperties();
    props.getHeaders().put(AmqpNames.HEADER_ATTEMPT, 1);
    Message message = new Message("retry-me".getBytes(), props);

    rabbitTemplate.send(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_QUERIES_RETRY_5S, message);

    // Once the 5s TTL elapses, the broker dead-letters it back onto
    // platform.queries with the fixed query routing key, landing on
    // queries.users-service -- proving the whole retry-tier chain (TTL +
    // dead-letter-exchange + dead-letter-routing-key) is wired correctly
    // for the query queue's own tiers, independent of the command tiers.
    await()
        .atMost(Duration.ofSeconds(8))
        .untilAsserted(
            () -> {
              Message redelivered = rabbitTemplate.receive(AmqpNames.QUEUE_QUERIES_DLQ);
              assertThat(redelivered).isNotNull();
              assertThat(new String(redelivered.getBody())).isEqualTo("retry-me");
            });
  }

  @Test
  void eventPublishedWithANonMatchingRoutingKeyDoesNotReachAWildcardBoundQueue() {
    Queue testQueue = amqpAdmin.declareQueue();
    Binding binding = BindingBuilder.bind(testQueue).to(eventsExchange).with("identity.person.*");
    amqpAdmin.declareBinding(binding);
    try {
      rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_EVENTS, "identity.account.created", "unrelated");

      Message received = rabbitTemplate.receive(testQueue.getName(), 500);
      assertThat(received).isNull();
    } finally {
      amqpAdmin.deleteQueue(testQueue.getName());
    }
  }
}
