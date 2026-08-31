package com.estoma.registros.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Declares the RabbitMQ topology registros-service owns: the shared platform
 * exchanges, its command queue with DLQ, and its consumer queue for
 * jornadas-service's domain events (needed to keep the local Jornada
 * projection current — registros never joins across service databases).
 *
 * <p>V1 simplification (Saturday demo scope): no retry tiers yet — a
 * rejected/failed message lands directly on its DLQ.
 */
@Configuration
public class AmqpTopologyConfig {

  @Bean
  public DirectExchange commandsExchange() {
    return new DirectExchange(AmqpNames.EXCHANGE_COMMANDS, true, false);
  }

  @Bean
  public DirectExchange resultsExchange() {
    return new DirectExchange(AmqpNames.EXCHANGE_RESULTS, true, false);
  }

  @Bean
  public TopicExchange eventsExchange() {
    return new TopicExchange(AmqpNames.EXCHANGE_EVENTS, true, false);
  }

  @Bean
  public TopicExchange deadLetterExchange() {
    return new TopicExchange(AmqpNames.EXCHANGE_DLX, true, false);
  }

  @Bean
  public Declarables registrosCommandTopology(
      DirectExchange commandsExchange, TopicExchange deadLetterExchange) {
    Queue commandQueue =
        QueueBuilder.durable(AmqpNames.QUEUE_COMMANDS)
            .withArgument("x-dead-letter-exchange", AmqpNames.EXCHANGE_DLX)
            .withArgument("x-dead-letter-routing-key", AmqpNames.ROUTING_KEY_DLQ)
            .build();
    Binding commandBinding =
        BindingBuilder.bind(commandQueue).to(commandsExchange).with(AmqpNames.ROUTING_KEY_COMMANDS);

    Queue dlq = QueueBuilder.durable(AmqpNames.QUEUE_COMMANDS_DLQ).build();
    Binding dlqBinding =
        BindingBuilder.bind(dlq).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_DLQ);

    return new Declarables(commandQueue, commandBinding, dlq, dlqBinding);
  }

  @Bean
  public Declarables registrosJornadasEventTopology(
      TopicExchange eventsExchange, TopicExchange deadLetterExchange) {
    Queue eventQueue =
        QueueBuilder.durable(AmqpNames.QUEUE_EVENTS)
            .withArgument("x-dead-letter-exchange", AmqpNames.EXCHANGE_DLX)
            .withArgument("x-dead-letter-routing-key", AmqpNames.ROUTING_KEY_EVENTS_DLQ)
            .build();
    Binding eventBinding =
        BindingBuilder.bind(eventQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_JORNADAS_EVENTS);

    Queue dlq = QueueBuilder.durable(AmqpNames.QUEUE_EVENTS_DLQ).build();
    Binding dlqBinding =
        BindingBuilder.bind(dlq).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_EVENTS_DLQ);

    return new Declarables(eventQueue, eventBinding, dlq, dlqBinding);
  }
}
