package com.estoma.registros.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * NOT part of the platform contract — see jornadas-service's
 * DemoObserverTopologyConfig for the full rationale. Declares two throwaway
 * queues so a developer can open the RabbitMQ management UI and immediately
 * see every Result/Event this service produces, with zero manual
 * queue/binding setup. Disable with {@code
 * estoma.registros.demo-observer-queues=false}.
 */
@Configuration
@ConditionalOnProperty(
    prefix = "estoma.registros",
    name = "demo-observer-queues",
    havingValue = "true",
    matchIfMissing = true)
public class DemoObserverTopologyConfig {

  // Same queue name as jornadas-service's DemoObserverTopologyConfig on
  // purpose: RabbitMQ's queue declaration is idempotent, so both services
  // converge on one shared "every Result bound for the BFF" observer queue.
  private static final String QUEUE_DEMO_RESULTS = "demo.results.bff";
  private static final String QUEUE_DEMO_EVENTS = "demo.events.registros";

  @Bean
  public Declarables demoObserverTopology(DirectExchange resultsExchange, TopicExchange eventsExchange) {
    Queue resultsQueue = QueueBuilder.durable(QUEUE_DEMO_RESULTS).build();
    Binding resultsBinding =
        BindingBuilder.bind(resultsQueue).to(resultsExchange).with(AmqpNames.ROUTING_KEY_RESULTS_BFF);

    Queue eventsQueue = QueueBuilder.durable(QUEUE_DEMO_EVENTS).build();
    Binding eventsBinding =
        BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.LOGICAL_COMPONENT_ID + ".#");

    return new Declarables(resultsQueue, resultsBinding, eventsQueue, eventsBinding);
  }
}
