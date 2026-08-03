package com.estoma.users.messaging;

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
 * Declares the RabbitMQ topology this service depends on: the four fixed
 * exchanges, its own command queue, its DLQ, and its three retry tiers
 * (docs/system-contract.md §9).
 *
 * <p>Gated behind {@code estoma.messaging.topology-declare} — {@code true}
 * in dev/test, where the app self-provisions everything against a plain
 * broker; {@code false} in staging/prod, where {@code estoma-infra}
 * provisions the same topology via GitOps and the runtime AMQP user has no
 * {@code configure} permission.
 *
 * <p>Only declares what this service OWNS. It deliberately does not
 * declare {@code results.bff} or {@code events.bff-projections} — those
 * are the BFF's queues and bindings to own (docs/system-contract.md §9,
 * §14 risks R1/R9).
 */
@Configuration
@ConditionalOnProperty(
    prefix = "estoma.messaging",
    name = "topology-declare",
    havingValue = "true",
    matchIfMissing = true)
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

  /**
   * The command queue, its DLQ, and its three retry tiers, all bundled as
   * one {@link Declarables} bean so the RabbitAdmin declares them
   * together. Never rely on the default dead-letter behaviour of
   * preserving the original routing key: a command dead-lettered from
   * {@code commands.users-service} would otherwise arrive at {@code
   * platform.dlx} with key {@code users-service}, colliding with the
   * commands namespace — every queue below sets its dead-letter routing
   * key explicitly.
   */
  @Bean
  public Declarables userServiceCommandTopology(
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

    Queue retry5s = retryTierQueue(AmqpNames.QUEUE_RETRY_5S, AmqpNames.RETRY_TTL_5S_MS);
    Binding retry5sBinding =
        BindingBuilder.bind(retry5s).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_RETRY_5S);

    Queue retry30s = retryTierQueue(AmqpNames.QUEUE_RETRY_30S, AmqpNames.RETRY_TTL_30S_MS);
    Binding retry30sBinding =
        BindingBuilder.bind(retry30s).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_RETRY_30S);

    Queue retry300s = retryTierQueue(AmqpNames.QUEUE_RETRY_300S, AmqpNames.RETRY_TTL_300S_MS);
    Binding retry300sBinding =
        BindingBuilder.bind(retry300s).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_RETRY_300S);

    return new Declarables(
        commandQueue,
        commandBinding,
        dlq,
        dlqBinding,
        retry5s,
        retry5sBinding,
        retry30s,
        retry30sBinding,
        retry300s,
        retry300sBinding);
  }

  /**
   * A retry tier queue: messages sit here for {@code ttlMillis}, then
   * dead-letter back onto {@code platform.commands} with the original
   * command routing key, re-entering {@code commands.users-service} for
   * another attempt.
   */
  private static Queue retryTierQueue(String name, long ttlMillis) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttlMillis)
        .withArgument("x-dead-letter-exchange", AmqpNames.EXCHANGE_COMMANDS)
        .withArgument("x-dead-letter-routing-key", AmqpNames.ROUTING_KEY_COMMANDS)
        .build();
  }
}
