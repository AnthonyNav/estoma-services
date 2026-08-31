package com.estoma.jornadas.messaging;

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
 * NOT part of the platform contract. In production, {@code results.bff} and
 * {@code events.bff-projections} are the BFF's own queues to declare — see
 * {@link AmqpTopologyConfig}'s javadoc and messaging-architecture.md §4.
 *
 * <p>Since there is no BFF running yet, this declares two throwaway queues
 * so a developer can open the RabbitMQ management UI
 * (http://localhost:15672, guest/guest) right after `docker compose up` and
 * immediately see every Result and Event this service produces, with zero
 * manual queue/binding setup. Disable with {@code
 * estoma.jornadas.demo-observer-queues=false} (matches {@code
 * estoma.messaging.topology-declare}'s gating pattern, off wherever this
 * service can't declare topology anyway).
 */
@Configuration
@ConditionalOnProperty(
    prefix = "estoma.jornadas",
    name = "demo-observer-queues",
    havingValue = "true",
    matchIfMissing = true)
public class DemoObserverTopologyConfig {

  private static final String QUEUE_DEMO_RESULTS = "demo.results.bff";
  private static final String QUEUE_DEMO_EVENTS = "demo.events.jornadas";

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
