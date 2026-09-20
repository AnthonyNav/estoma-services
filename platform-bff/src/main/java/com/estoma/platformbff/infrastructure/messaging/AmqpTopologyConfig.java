package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(
    prefix = "platform.messaging",
    name = "topology-declare",
    havingValue = "true",
    matchIfMissing = true)
public class AmqpTopologyConfig {
  @Bean
  DirectExchange commandsExchange() {
    return new DirectExchange(MessagingTopology.COMMANDS_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange resultsExchange() {
    return new DirectExchange(MessagingTopology.RESULTS_EXCHANGE, true, false);
  }

  @Bean
  TopicExchange eventsExchange() {
    return new TopicExchange(MessagingTopology.EVENTS_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange queriesExchange() {
    return new DirectExchange(MessagingTopology.QUERIES_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange securityRpcExchange() {
    return new DirectExchange(MessagingTopology.SECURITY_RPC_EXCHANGE, true, false);
  }

  @Bean
  Declarables bffResultsTopology(DirectExchange resultsExchange) {
    var queue = QueueBuilder.durable(MessagingTopology.RESULTS_QUEUE).build();
    return new Declarables(
        queue,
        BindingBuilder.bind(queue).to(resultsExchange).with("bff"));
  }

  @Bean
  Declarables bffSecurityProjectionTopology(TopicExchange eventsExchange) {
    var queue = QueueBuilder.durable(MessagingTopology.PROJECTION_EVENTS_QUEUE).build();
    return new Declarables(
        queue,
        BindingBuilder.bind(queue).to(eventsExchange).with("authentication.account.created"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authentication.account.presentation-updated"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authentication.account.deactivated"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authentication.session.revoked"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authentication.session.all-revoked"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authentication.session.other-revoked"),
        BindingBuilder.bind(queue).to(eventsExchange).with("users.person.created"),
        BindingBuilder.bind(queue).to(eventsExchange).with("users.person.updated"),
        BindingBuilder.bind(queue).to(eventsExchange).with("users.person.activated"),
        BindingBuilder.bind(queue).to(eventsExchange).with("users.person.deactivated"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authorization.account-role.assigned"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authorization.account-role.changed"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authorization.system-access.granted"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authorization.system-access.suspended"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authorization.system-access.revoked"),
        BindingBuilder.bind(queue).to(eventsExchange).with("authorization.system-access.restored"),
        BindingBuilder.bind(queue).to(eventsExchange).with("academic.#"),
        BindingBuilder.bind(queue).to(eventsExchange).with("communications.inbox-message.#"),
        BindingBuilder.bind(queue).to(eventsExchange).with("communications.inbox.visited"),
        BindingBuilder.bind(queue).to(eventsExchange).with("wash-administration.#"),
        BindingBuilder.bind(queue).to(eventsExchange).with("appointments.#"),
        BindingBuilder.bind(queue).to(eventsExchange).with("wash-execution.#"),
        BindingBuilder.bind(queue).to(eventsExchange).with("jornadas.#"),
        BindingBuilder.bind(queue).to(eventsExchange).with("registros.#"));
  }
}
