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
 */
class AmqpTopologyConfigIT extends AbstractMessagingIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private TopicExchange eventsExchange;

  @Test
  void commandPublishedToCommandsExchangeArrivesOnTheCommandQueue() {
    String body = "test-" + UUID.randomUUID();

    rabbitTemplate.convertAndSend(
        AmqpNames.EXCHANGE_COMMANDS, AmqpNames.ROUTING_KEY_COMMANDS, body);

    Message received = rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS, 2_000);
    assertThat(received).isNotNull();
  }

  @Test
  void messagePublishedWithTheDlqRoutingKeyArrivesOnTheDlq() {
    String body = "test-" + UUID.randomUUID();

    rabbitTemplate.convertAndSend(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_DLQ, body);

    Message received = rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS_DLQ, 2_000);
    assertThat(received).isNotNull();
  }

  @Test
  void messageRoutedToTheFiveSecondRetryTierReappearsOnTheCommandQueueAfterItsTtlExpires() {
    // Drain anything left over from other tests sharing this broker.
    // receive(name) (no timeout) does a single non-blocking basic.get;
    // receive(name, 0) instead opens a consumer with a 0ms wait, which
    // throws ConsumeOkNotReceivedException rather than returning null.
    while (rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS) != null) {
      // drain
    }

    MessageProperties props = new MessageProperties();
    props.getHeaders().put(AmqpNames.HEADER_ATTEMPT, 1);
    Message message = new Message("retry-me".getBytes(), props);

    rabbitTemplate.send(AmqpNames.EXCHANGE_DLX, AmqpNames.ROUTING_KEY_RETRY_5S, message);

    // Immediately after publishing, the message must NOT be on the
    // command queue yet -- it is sitting in the retry tier waiting out
    // its TTL.
    assertThat(rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS, 200)).isNull();

    // Once the 5s TTL elapses, the broker dead-letters it back onto
    // platform.commands with the fixed command routing key, and it
    // reappears here -- proving the whole retry-tier chain (TTL +
    // dead-letter-exchange + dead-letter-routing-key) is wired correctly,
    // not just that the beans were created.
    await()
        .atMost(Duration.ofSeconds(8))
        .untilAsserted(
            () -> {
              Message redelivered = rabbitTemplate.receive(AmqpNames.QUEUE_COMMANDS, 500);
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
