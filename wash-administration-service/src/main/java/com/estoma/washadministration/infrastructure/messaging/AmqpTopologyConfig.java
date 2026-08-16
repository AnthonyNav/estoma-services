package com.estoma.washadministration.infrastructure.messaging;

import com.estoma.washadministration.contracts.MessagingNames;
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

@Configuration
@ConditionalOnProperty(
    prefix = "estoma.messaging", name = "topology-declare", havingValue = "true", matchIfMissing = true)
public class AmqpTopologyConfig {

  @Bean
  DirectExchange commandsExchange() {
    return new DirectExchange(MessagingNames.COMMANDS_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange queriesExchange() {
    return new DirectExchange(MessagingNames.QUERIES_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange resultsExchange() {
    return new DirectExchange(MessagingNames.RESULTS_EXCHANGE, true, false);
  }

  @Bean
  TopicExchange eventsExchange() {
    return new TopicExchange(MessagingNames.EVENTS_EXCHANGE, true, false);
  }

  @Bean
  TopicExchange deadLetterExchange() {
    return new TopicExchange(MessagingNames.DLX_EXCHANGE, true, false);
  }

  @Bean
  Declarables commandTopology(DirectExchange commandsExchange, TopicExchange deadLetterExchange) {
    Queue main = mainQueue(MessagingNames.COMMAND_QUEUE, MessagingNames.COMMAND_DLQ);
    Binding mainBinding = BindingBuilder.bind(main).to(commandsExchange).with(MessagingNames.COMMAND_ROUTING_KEY);
    Queue retry1 = retryQueue(MessagingNames.COMMAND_RETRY_1, AmqpNames.RETRY_1_TTL_MILLIS, MessagingNames.COMMANDS_EXCHANGE, MessagingNames.COMMAND_ROUTING_KEY);
    Queue retry2 = retryQueue(MessagingNames.COMMAND_RETRY_2, AmqpNames.RETRY_2_TTL_MILLIS, MessagingNames.COMMANDS_EXCHANGE, MessagingNames.COMMAND_ROUTING_KEY);
    Queue dlq = QueueBuilder.durable(MessagingNames.COMMAND_DLQ).build();
    return new Declarables(
        main,
        mainBinding,
        retry1,
        BindingBuilder.bind(retry1).to(deadLetterExchange).with(MessagingNames.COMMAND_RETRY_1),
        retry2,
        BindingBuilder.bind(retry2).to(deadLetterExchange).with(MessagingNames.COMMAND_RETRY_2),
        dlq,
        BindingBuilder.bind(dlq).to(deadLetterExchange).with(MessagingNames.COMMAND_DLQ));
  }

  @Bean
  Declarables queryTopology(DirectExchange queriesExchange, TopicExchange deadLetterExchange) {
    Queue main = mainQueue(MessagingNames.QUERY_QUEUE, MessagingNames.QUERY_DLQ);
    Binding mainBinding = BindingBuilder.bind(main).to(queriesExchange).with(MessagingNames.QUERY_ROUTING_KEY);
    Queue retry1 = retryQueue(MessagingNames.QUERY_RETRY_1, AmqpNames.RETRY_1_TTL_MILLIS, MessagingNames.QUERIES_EXCHANGE, MessagingNames.QUERY_ROUTING_KEY);
    Queue retry2 = retryQueue(MessagingNames.QUERY_RETRY_2, AmqpNames.RETRY_2_TTL_MILLIS, MessagingNames.QUERIES_EXCHANGE, MessagingNames.QUERY_ROUTING_KEY);
    Queue dlq = QueueBuilder.durable(MessagingNames.QUERY_DLQ).build();
    return new Declarables(
        main,
        mainBinding,
        retry1,
        BindingBuilder.bind(retry1).to(deadLetterExchange).with(MessagingNames.QUERY_RETRY_1),
        retry2,
        BindingBuilder.bind(retry2).to(deadLetterExchange).with(MessagingNames.QUERY_RETRY_2),
        dlq,
        BindingBuilder.bind(dlq).to(deadLetterExchange).with(MessagingNames.QUERY_DLQ));
  }

  @Bean
  Declarables eventsTopology(TopicExchange eventsExchange, TopicExchange deadLetterExchange) {
    Queue main = mainQueue(MessagingNames.EVENTS_QUEUE, MessagingNames.EVENTS_DLQ);
    Declarables declarations = new Declarables(main);
    declarations.getDeclarables().add(BindingBuilder.bind(main).to(eventsExchange).with("authorization.account-role.*"));
    declarations.getDeclarables().add(BindingBuilder.bind(main).to(eventsExchange).with("authorization.system-access.*"));
    Queue retry1 = topicRetryQueue(MessagingNames.EVENTS_RETRY_1, AmqpNames.RETRY_1_TTL_MILLIS, MessagingNames.EVENTS_QUEUE);
    Queue retry2 = topicRetryQueue(MessagingNames.EVENTS_RETRY_2, AmqpNames.RETRY_2_TTL_MILLIS, MessagingNames.EVENTS_QUEUE);
    Queue dlq = QueueBuilder.durable(MessagingNames.EVENTS_DLQ).build();
    declarations.getDeclarables().add(retry1);
    declarations.getDeclarables().add(BindingBuilder.bind(retry1).to(deadLetterExchange).with(MessagingNames.EVENTS_RETRY_1));
    declarations.getDeclarables().add(retry2);
    declarations.getDeclarables().add(BindingBuilder.bind(retry2).to(deadLetterExchange).with(MessagingNames.EVENTS_RETRY_2));
    declarations.getDeclarables().add(dlq);
    declarations.getDeclarables().add(BindingBuilder.bind(dlq).to(deadLetterExchange).with(MessagingNames.EVENTS_DLQ));
    return declarations;
  }

  @Bean
  Declarables resultsTopology(DirectExchange resultsExchange, TopicExchange deadLetterExchange) {
    Queue main = mainQueue(MessagingNames.RESULTS_QUEUE, MessagingNames.RESULTS_DLQ);
    Binding mainBinding = BindingBuilder.bind(main).to(resultsExchange).with(MessagingNames.LOGICAL_COMPONENT_ID);
    Queue retry1 = topicRetryQueue(MessagingNames.RESULTS_RETRY_1, AmqpNames.RETRY_1_TTL_MILLIS, MessagingNames.RESULTS_QUEUE);
    Queue retry2 = topicRetryQueue(MessagingNames.RESULTS_RETRY_2, AmqpNames.RETRY_2_TTL_MILLIS, MessagingNames.RESULTS_QUEUE);
    Queue dlq = QueueBuilder.durable(MessagingNames.RESULTS_DLQ).build();
    return new Declarables(
        main,
        mainBinding,
        retry1,
        BindingBuilder.bind(retry1).to(deadLetterExchange).with(MessagingNames.RESULTS_RETRY_1),
        retry2,
        BindingBuilder.bind(retry2).to(deadLetterExchange).with(MessagingNames.RESULTS_RETRY_2),
        dlq,
        BindingBuilder.bind(dlq).to(deadLetterExchange).with(MessagingNames.RESULTS_DLQ));
  }

  private static Queue mainQueue(String name, String dlqRoutingKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-dead-letter-exchange", MessagingNames.DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", dlqRoutingKey)
        .build();
  }

  private static Queue retryQueue(String name, long ttl, String targetExchange, String targetRoutingKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttl)
        .withArgument("x-dead-letter-exchange", targetExchange)
        .withArgument("x-dead-letter-routing-key", targetRoutingKey)
        .build();
  }

  private static Queue topicRetryQueue(String name, long ttl, String targetQueue) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttl)
        .withArgument("x-dead-letter-exchange", "")
        .withArgument("x-dead-letter-routing-key", targetQueue)
        .build();
  }
}
