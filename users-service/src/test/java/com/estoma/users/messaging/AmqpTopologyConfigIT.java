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

class AmqpTopologyConfigIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private TopicExchange eventsExchange;

  @Test
  void commandPublishedToCanonicalCommandsRouteIsConsumedAndInvalidPayloadReachesDlq() {
    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, "test-" + UUID.randomUUID());
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNotNull());
  }

  @Test
  void messagePublishedWithTheDlqRoutingKeyArrivesOnTheDlq() {
    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_DLQ, "test-" + UUID.randomUUID());
    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ, 2_000)).isNotNull();
  }

  @Test
  void messageRoutedToTheFiveSecondRetryTierReappearsAndReachesDlq() {
    MessageProperties props = new MessageProperties();
    props.getHeaders().put(AmqpNames.HEADER_ATTEMPT, 1);
    rabbitTemplate.send(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_RETRY_5S,
        new Message("retry-me".getBytes(), props));
    await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
      Message redelivered = rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ);
      assertThat(redelivered).isNotNull();
      assertThat(new String(redelivered.getBody())).isEqualTo("retry-me");
    });
  }

  @Test
  void eventPublishedWithCanonicalUsersRoutingKeyReachesWildcardConsumer() {
    Queue testQueue = amqpAdmin.declareQueue();
    Binding binding = BindingBuilder.bind(testQueue).to(eventsExchange).with("users.person.*");
    amqpAdmin.declareBinding(binding);
    try {
      rabbitTemplate.convertAndSend(
          AmqpNames.EXCHANGE_EVENTS, AmqpNames.ROUTING_KEY_PERSON_CREATED, "person-created-test");
      assertThat(rabbitTemplate.receive(testQueue.getName(), 2_000)).isNotNull();
    } finally {
      amqpAdmin.deleteQueue(testQueue.getName());
    }
  }

  @Test
  void queryPublishedToCanonicalQueriesRouteIsConsumedAndInvalidPayloadReachesItsDlq() {
    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_QUERIES, AmqpNames.ROUTING_KEY_QUERIES, "test-" + UUID.randomUUID());
    await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
        assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_QUERIES_DLQ)).isNotNull());
    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ)).isNull();
  }

  @Test
  void messagePublishedWithTheQueriesDlqRoutingKeyArrivesOnTheQueriesDlq() {
    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_QUERIES_DLQ, "test-" + UUID.randomUUID());
    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_QUERIES_DLQ, 2_000)).isNotNull();
  }

  @Test
  void queryRetryTierReappearsOnQueriesAndThenReachesItsOwnDlq() {
    MessageProperties props = new MessageProperties();
    props.getHeaders().put(AmqpNames.HEADER_ATTEMPT, 1);
    rabbitTemplate.send(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_QUERIES_RETRY_5S,
        new Message("retry-me".getBytes(), props));
    await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
      Message redelivered = rabbitTemplate.receive(AmqpNames.QUEUE_QUERIES_DLQ);
      assertThat(redelivered).isNotNull();
      assertThat(new String(redelivered.getBody())).isEqualTo("retry-me");
    });
  }

  @Test
  void unrelatedUsersEventDoesNotReachPersonWildcard() {
    Queue testQueue = amqpAdmin.declareQueue();
    Binding binding = BindingBuilder.bind(testQueue).to(eventsExchange).with("users.person.*");
    amqpAdmin.declareBinding(binding);
    try {
      rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_EVENTS, "users.account.created", "unrelated");
      assertThat(rabbitTemplate.receive(testQueue.getName(), 500)).isNull();
    } finally {
      amqpAdmin.deleteQueue(testQueue.getName());
    }
  }
}
