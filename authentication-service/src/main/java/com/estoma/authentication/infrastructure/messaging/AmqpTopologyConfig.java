package com.estoma.authentication.infrastructure.messaging;

import com.estoma.authentication.contracts.MessagingNames;
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

  @Bean public DirectExchange commandsExchange() { return new DirectExchange(MessagingNames.COMMANDS_EXCHANGE, true, false); }
  @Bean public DirectExchange resultsExchange() { return new DirectExchange(MessagingNames.RESULTS_EXCHANGE, true, false); }
  @Bean public TopicExchange eventsExchange() { return new TopicExchange(MessagingNames.EVENTS_EXCHANGE, true, false); }
  @Bean public DirectExchange queriesExchange() { return new DirectExchange(MessagingNames.QUERIES_EXCHANGE, true, false); }
  @Bean public DirectExchange securityRpcExchange() { return new DirectExchange(MessagingNames.SECURITY_RPC_EXCHANGE, true, false); }
  @Bean public TopicExchange deadLetterExchange() { return new TopicExchange(AmqpNames.EXCHANGE_DLX, true, false); }

  @Bean
  public Declarables commandTopology(DirectExchange commandsExchange, TopicExchange deadLetterExchange) {
    Queue queue = durableSource(MessagingNames.AUTHENTICATION_COMMAND_QUEUE, AmqpNames.ROUTING_KEY_COMMANDS_DLQ);
    Queue dlq = QueueBuilder.durable(AmqpNames.QUEUE_COMMANDS_DLQ).build();
    Queue r1 = retryTierQueue(AmqpNames.QUEUE_COMMANDS_RETRY_5S, AmqpNames.RETRY_TTL_5S_MS, MessagingNames.COMMANDS_EXCHANGE, AmqpNames.PRODUCER);
    Queue r2 = retryTierQueue(AmqpNames.QUEUE_COMMANDS_RETRY_30S, AmqpNames.RETRY_TTL_30S_MS, MessagingNames.COMMANDS_EXCHANGE, AmqpNames.PRODUCER);
    Queue r3 = retryTierQueue(AmqpNames.QUEUE_COMMANDS_RETRY_300S, AmqpNames.RETRY_TTL_300S_MS, MessagingNames.COMMANDS_EXCHANGE, AmqpNames.PRODUCER);
    return new Declarables(
        queue, BindingBuilder.bind(queue).to(commandsExchange).with(AmqpNames.PRODUCER),
        dlq, BindingBuilder.bind(dlq).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_COMMANDS_DLQ),
        r1, BindingBuilder.bind(r1).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_COMMANDS_RETRY_5S),
        r2, BindingBuilder.bind(r2).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_COMMANDS_RETRY_30S),
        r3, BindingBuilder.bind(r3).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_COMMANDS_RETRY_300S));
  }

  @Bean
  public Declarables queryTopology(DirectExchange queriesExchange, TopicExchange deadLetterExchange) {
    Queue queue = durableSource(MessagingNames.AUTHENTICATION_QUERY_QUEUE, AmqpNames.ROUTING_KEY_QUERIES_DLQ);
    Queue dlq = QueueBuilder.durable(AmqpNames.QUEUE_QUERIES_DLQ).build();
    Queue r1 = retryTierQueue(AmqpNames.QUEUE_QUERIES_RETRY_5S, AmqpNames.RETRY_TTL_5S_MS, MessagingNames.QUERIES_EXCHANGE, AmqpNames.PRODUCER);
    Queue r2 = retryTierQueue(AmqpNames.QUEUE_QUERIES_RETRY_30S, AmqpNames.RETRY_TTL_30S_MS, MessagingNames.QUERIES_EXCHANGE, AmqpNames.PRODUCER);
    Queue r3 = retryTierQueue(AmqpNames.QUEUE_QUERIES_RETRY_300S, AmqpNames.RETRY_TTL_300S_MS, MessagingNames.QUERIES_EXCHANGE, AmqpNames.PRODUCER);
    return new Declarables(
        queue, BindingBuilder.bind(queue).to(queriesExchange).with(AmqpNames.PRODUCER),
        dlq, BindingBuilder.bind(dlq).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_QUERIES_DLQ),
        r1, BindingBuilder.bind(r1).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_QUERIES_RETRY_5S),
        r2, BindingBuilder.bind(r2).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_QUERIES_RETRY_30S),
        r3, BindingBuilder.bind(r3).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_QUERIES_RETRY_300S));
  }

  @Bean
  public Declarables eventsTopology(TopicExchange eventsExchange, TopicExchange deadLetterExchange) {
    Queue eventsQueue = durableSource(AmqpNames.QUEUE_EVENTS, AmqpNames.ROUTING_KEY_EVENTS_DLQ);
    Queue dlq = QueueBuilder.durable(AmqpNames.QUEUE_EVENTS_DLQ).build();
    Queue retry = retryTierQueueTopic(AmqpNames.QUEUE_EVENTS_RETRY_5S, AmqpNames.RETRY_TTL_5S_MS, AmqpNames.QUEUE_EVENTS);
    return new Declarables(
        eventsQueue,
        BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_PERSON_CREATED),
        BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_PERSON_ACTIVATED),
        BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_PERSON_DEACTIVATED),
        BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_STUDENT_NUMBER_CORRECTED),
        dlq, BindingBuilder.bind(dlq).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_EVENTS_DLQ),
        retry, BindingBuilder.bind(retry).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_EVENTS_RETRY_5S));
  }

  @Bean
  public Declarables rpcTopology(DirectExchange securityRpcExchange) {
    Queue rpcQueue = QueueBuilder.durable(MessagingNames.AUTHENTICATION_RPC_QUEUE)
        .withArgument("x-message-ttl", AmqpNames.RPC_MESSAGE_TTL_MS).build();
    Binding rpcBinding = BindingBuilder.bind(rpcQueue).to(securityRpcExchange).with(MessagingNames.AUTHENTICATION_RPC_QUEUE);
    return new Declarables(rpcQueue, rpcBinding);
  }

  private static Queue durableSource(String name, String dlqKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-dead-letter-exchange", AmqpNames.EXCHANGE_DLX)
        .withArgument("x-dead-letter-routing-key", dlqKey)
        .build();
  }

  private static Queue retryTierQueue(String name, long ttl, String exchange, String key) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttl)
        .withArgument("x-dead-letter-exchange", exchange)
        .withArgument("x-dead-letter-routing-key", key)
        .build();
  }

  private static Queue retryTierQueueTopic(String name, long ttl, String targetQueue) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttl)
        .withArgument("x-dead-letter-exchange", "")
        .withArgument("x-dead-letter-routing-key", targetQueue)
        .build();
  }
}
