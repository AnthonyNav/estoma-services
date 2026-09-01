package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.rabbitmq.client.BuiltinExchangeType;
import com.rabbitmq.client.ConnectionFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Verifies the actual broker routing boundary for the Communications event queue. */
@Testcontainers
class AmqpTopologyRoutingIT {

  @Container
  static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management-alpine");

  @Test
  void routesOnlyRecipientAccountEventsAndExcludesPresentationAndIdentifierChanges() throws Exception {
    String suffix = UUID.randomUUID().toString();
    String exchangeName = "test.events." + suffix;
    String queueName = "test.events.communications." + suffix;
    ConnectionFactory factory = connectionFactory();

    try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
      channel.exchangeDeclare(exchangeName, BuiltinExchangeType.TOPIC, false);
      channel.queueDeclare(queueName, false, false, true, null);
      AmqpTopologyConfig topology = new AmqpTopologyConfig();
      Queue queue = new Queue(queueName, false, false, true);
      TopicExchange exchange = new TopicExchange(exchangeName, false, false);
      for (var binding : AmqpTopologyConfigTest.accountBindings(topology, queue, exchange)) {
        channel.queueBind(queueName, exchangeName, binding.getRoutingKey());
      }

      channel.basicPublish(exchangeName, "authentication.account.created", null, new byte[] {1});
      assertThat(channel.basicGet(queueName, true)).isNotNull();

      channel.basicPublish(exchangeName, "authentication.account.presentation-updated", null, new byte[] {2});
      assertThat(channel.basicGet(queueName, true)).isNull();

      channel.basicPublish(exchangeName, "authentication.account.login-identifier-changed", null, new byte[] {3});
      assertThat(channel.basicGet(queueName, true)).isNull();
    }
  }

  private static ConnectionFactory connectionFactory() {
    ConnectionFactory factory = new ConnectionFactory();
    factory.setHost(RABBITMQ.getHost());
    factory.setPort(RABBITMQ.getAmqpPort());
    factory.setUsername(RABBITMQ.getAdminUsername());
    factory.setPassword(RABBITMQ.getAdminPassword());
    return factory;
  }
}
