package com.estoma.authorization.infrastructure.messaging;

import com.estoma.authorization.contracts.MessagingNames;
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
class AmqpTopologyConfig {

  @Bean
  DirectExchange authorizationCommandsExchange() {
    return new DirectExchange(MessagingNames.COMMANDS_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange authorizationQueriesExchange() {
    return new DirectExchange(MessagingNames.QUERIES_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange authorizationResultsExchange() {
    return new DirectExchange(MessagingNames.RESULTS_EXCHANGE, true, false);
  }

  @Bean
  TopicExchange authorizationEventsExchange() {
    return new TopicExchange(MessagingNames.EVENTS_EXCHANGE, true, false);
  }

  @Bean
  TopicExchange authorizationDeadLetterExchange() {
    return new TopicExchange(MessagingNames.DLX_EXCHANGE, true, false);
  }

  @Bean
  Declarables authorizationCommandTopology(
      DirectExchange authorizationCommandsExchange,
      TopicExchange authorizationDeadLetterExchange,
      MessagingProperties properties) {
    Queue main = mainQueue(MessagingNames.COMMAND_QUEUE, MessagingNames.COMMAND_DLQ);
    Queue retry1 = retryQueue(
        MessagingNames.COMMAND_RETRY_1,
        properties.retry1Ttl().toMillis(),
        MessagingNames.COMMANDS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);
    Queue retry2 = retryQueue(
        MessagingNames.COMMAND_RETRY_2,
        properties.retry2Ttl().toMillis(),
        MessagingNames.COMMANDS_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);
    Queue dlq = QueueBuilder.durable(MessagingNames.COMMAND_DLQ).build();
    return new Declarables(
        main,
        BindingBuilder.bind(main).to(authorizationCommandsExchange).with(MessagingNames.LOGICAL_COMPONENT_ID),
        retry1,
        BindingBuilder.bind(retry1).to(authorizationDeadLetterExchange).with(MessagingNames.COMMAND_RETRY_1),
        retry2,
        BindingBuilder.bind(retry2).to(authorizationDeadLetterExchange).with(MessagingNames.COMMAND_RETRY_2),
        dlq,
        BindingBuilder.bind(dlq).to(authorizationDeadLetterExchange).with(MessagingNames.COMMAND_DLQ));
  }

  @Bean
  Declarables authorizationQueryTopology(
      DirectExchange authorizationQueriesExchange,
      TopicExchange authorizationDeadLetterExchange,
      MessagingProperties properties) {
    Queue main = mainQueue(MessagingNames.QUERY_QUEUE, MessagingNames.QUERY_DLQ);
    Queue retry1 = retryQueue(
        MessagingNames.QUERY_RETRY_1,
        properties.retry1Ttl().toMillis(),
        MessagingNames.QUERIES_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);
    Queue retry2 = retryQueue(
        MessagingNames.QUERY_RETRY_2,
        properties.retry2Ttl().toMillis(),
        MessagingNames.QUERIES_EXCHANGE,
        MessagingNames.LOGICAL_COMPONENT_ID);
    Queue dlq = QueueBuilder.durable(MessagingNames.QUERY_DLQ).build();
    return new Declarables(
        main,
        BindingBuilder.bind(main).to(authorizationQueriesExchange).with(MessagingNames.LOGICAL_COMPONENT_ID),
        retry1,
        BindingBuilder.bind(retry1).to(authorizationDeadLetterExchange).with(MessagingNames.QUERY_RETRY_1),
        retry2,
        BindingBuilder.bind(retry2).to(authorizationDeadLetterExchange).with(MessagingNames.QUERY_RETRY_2),
        dlq,
        BindingBuilder.bind(dlq).to(authorizationDeadLetterExchange).with(MessagingNames.QUERY_DLQ));
  }

  @Bean
  Declarables authorizationEventTopology(
      TopicExchange authorizationEventsExchange,
      TopicExchange authorizationDeadLetterExchange,
      MessagingProperties properties) {
    Queue main = mainQueue(MessagingNames.EVENT_QUEUE, MessagingNames.EVENT_DLQ);
    Queue retry1 = localRetryQueue(
        MessagingNames.EVENT_RETRY_1, properties.retry1Ttl().toMillis(), MessagingNames.EVENT_QUEUE);
    Queue retry2 = localRetryQueue(
        MessagingNames.EVENT_RETRY_2, properties.retry2Ttl().toMillis(), MessagingNames.EVENT_QUEUE);
    Queue dlq = QueueBuilder.durable(MessagingNames.EVENT_DLQ).build();
    return new Declarables(
        main,
        BindingBuilder.bind(main).to(authorizationEventsExchange).with("users.person.created"),
        BindingBuilder.bind(main).to(authorizationEventsExchange).with("users.person.activated"),
        BindingBuilder.bind(main).to(authorizationEventsExchange).with("users.person.deactivated"),
        BindingBuilder.bind(main).to(authorizationEventsExchange).with("authentication.account.created"),
        BindingBuilder.bind(main).to(authorizationEventsExchange).with("authentication.account.activated"),
        BindingBuilder.bind(main).to(authorizationEventsExchange).with("authentication.account.deactivated"),
        BindingBuilder.bind(main).to(authorizationEventsExchange).with("academic.student-eligibility.changed"),
        retry1,
        BindingBuilder.bind(retry1).to(authorizationDeadLetterExchange).with(MessagingNames.EVENT_RETRY_1),
        retry2,
        BindingBuilder.bind(retry2).to(authorizationDeadLetterExchange).with(MessagingNames.EVENT_RETRY_2),
        dlq,
        BindingBuilder.bind(dlq).to(authorizationDeadLetterExchange).with(MessagingNames.EVENT_DLQ));
  }

  @Bean
  Declarables authorizationResultTopology(
      DirectExchange authorizationResultsExchange,
      TopicExchange authorizationDeadLetterExchange,
      MessagingProperties properties) {
    Queue main = mainQueue(MessagingNames.RESULT_QUEUE, MessagingNames.RESULT_DLQ);
    Queue retry1 = localRetryQueue(
        MessagingNames.RESULT_RETRY_1, properties.retry1Ttl().toMillis(), MessagingNames.RESULT_QUEUE);
    Queue retry2 = localRetryQueue(
        MessagingNames.RESULT_RETRY_2, properties.retry2Ttl().toMillis(), MessagingNames.RESULT_QUEUE);
    Queue dlq = QueueBuilder.durable(MessagingNames.RESULT_DLQ).build();
    return new Declarables(
        main,
        BindingBuilder.bind(main).to(authorizationResultsExchange).with(MessagingNames.LOGICAL_COMPONENT_ID),
        retry1,
        BindingBuilder.bind(retry1).to(authorizationDeadLetterExchange).with(MessagingNames.RESULT_RETRY_1),
        retry2,
        BindingBuilder.bind(retry2).to(authorizationDeadLetterExchange).with(MessagingNames.RESULT_RETRY_2),
        dlq,
        BindingBuilder.bind(dlq).to(authorizationDeadLetterExchange).with(MessagingNames.RESULT_DLQ));
  }

  private static Queue mainQueue(String name, String dlqRoutingKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-dead-letter-exchange", MessagingNames.DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", dlqRoutingKey)
        .build();
  }

  private static Queue retryQueue(String name, long ttlMillis, String targetExchange, String targetRoutingKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttlMillis)
        .withArgument("x-dead-letter-exchange", targetExchange)
        .withArgument("x-dead-letter-routing-key", targetRoutingKey)
        .build();
  }

  private static Queue localRetryQueue(String name, long ttlMillis, String targetQueue) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttlMillis)
        .withArgument("x-dead-letter-exchange", "")
        .withArgument("x-dead-letter-routing-key", targetQueue)
        .build();
  }

}
