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

/**
 * Declares the RabbitMQ topology this service owns: the platform
 * exchanges, its own command queue with DLQ and 3 retry tiers, its events
 * queue with DLQ and a single retry tier, and {@code rpc.authentication}
 * with neither (system_contract.md "RPC Security Rules";
 * messaging-architecture.md rule 24).
 *
 * <p>Gated behind {@code estoma.messaging.topology-declare} -- {@code
 * true} in dev/test where the app self-provisions everything against a
 * plain broker, {@code false} in staging/prod where {@code estoma-infra}
 * provisions the same topology via GitOps. Does not declare {@code
 * results.bff}: that queue belongs to the BFF.
 *
 * <p>Every queue below sets its dead-letter routing key explicitly rather
 * than relying on the default of preserving the original routing key: a
 * command dead-lettered from {@code commands.authentication-service}
 * would otherwise arrive at {@code platform.dlx} with key {@code
 * authentication} ({@link AmqpNames#PRODUCER}, this service's routing
 * key on {@code platform.commands}), colliding with the commands
 * namespace.
 */
@Configuration
@ConditionalOnProperty(prefix = "estoma.messaging", name = "topology-declare", havingValue = "true", matchIfMissing = true)
public class AmqpTopologyConfig {

  @Bean
  public DirectExchange commandsExchange() {
    return new DirectExchange(MessagingNames.COMMANDS_EXCHANGE, true, false);
  }

  @Bean
  public DirectExchange resultsExchange() {
    return new DirectExchange(MessagingNames.RESULTS_EXCHANGE, true, false);
  }

  @Bean
  public TopicExchange eventsExchange() {
    return new TopicExchange(MessagingNames.EVENTS_EXCHANGE, true, false);
  }

  @Bean
  public DirectExchange securityRpcExchange() {
    return new DirectExchange(MessagingNames.SECURITY_RPC_EXCHANGE, true, false);
  }

  @Bean
  public TopicExchange deadLetterExchange() {
    return new TopicExchange(AmqpNames.EXCHANGE_DLX, true, false);
  }

  @Bean
  public Declarables commandTopology(DirectExchange commandsExchange, TopicExchange deadLetterExchange) {
    Queue commandQueue =
        QueueBuilder.durable(MessagingNames.AUTHENTICATION_COMMAND_QUEUE)
            .withArgument("x-dead-letter-exchange", AmqpNames.EXCHANGE_DLX)
            .withArgument("x-dead-letter-routing-key", AmqpNames.ROUTING_KEY_COMMANDS_DLQ)
            .build();
    // Routing key is this service's logicalComponentId ("authentication"),
    // per messaging-architecture.md §4: "Commands -> identificador lógico
    // del servicio propietario" -- NOT the queue name. The BFF publishes
    // with this exact routing key.
    Binding commandBinding = BindingBuilder.bind(commandQueue).to(commandsExchange).with(AmqpNames.PRODUCER);

    Queue dlq = QueueBuilder.durable(AmqpNames.QUEUE_COMMANDS_DLQ).build();
    Binding dlqBinding = BindingBuilder.bind(dlq).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_COMMANDS_DLQ);

    Queue retry5s = retryTierQueue(AmqpNames.QUEUE_COMMANDS_RETRY_5S, AmqpNames.RETRY_TTL_5S_MS, MessagingNames.COMMANDS_EXCHANGE, AmqpNames.PRODUCER);
    Binding retry5sBinding =
        BindingBuilder.bind(retry5s).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_COMMANDS_RETRY_5S);

    Queue retry30s = retryTierQueue(AmqpNames.QUEUE_COMMANDS_RETRY_30S, AmqpNames.RETRY_TTL_30S_MS, MessagingNames.COMMANDS_EXCHANGE, AmqpNames.PRODUCER);
    Binding retry30sBinding =
        BindingBuilder.bind(retry30s).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_COMMANDS_RETRY_30S);

    Queue retry300s = retryTierQueue(AmqpNames.QUEUE_COMMANDS_RETRY_300S, AmqpNames.RETRY_TTL_300S_MS, MessagingNames.COMMANDS_EXCHANGE, AmqpNames.PRODUCER);
    Binding retry300sBinding =
        BindingBuilder.bind(retry300s).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_COMMANDS_RETRY_300S);

    return new Declarables(
        commandQueue, commandBinding, dlq, dlqBinding, retry5s, retry5sBinding, retry30s, retry30sBinding, retry300s,
        retry300sBinding);
  }

  /**
   * {@code events.authentication-service}, its own DLQ, and a single 5s
   * retry tier -- simpler than the command queue's 3 tiers because
   * consumed events (`PersonCreated`/`PersonActivated`/`PersonDeactivated`/
   * `StudentNumberCorrected`) are idempotent projections; one retry
   * before DLQ is enough for the transient failures this service expects
   * (a wedged database connection, a lock timeout).
   */
  @Bean
  public Declarables eventsTopology(TopicExchange eventsExchange, TopicExchange deadLetterExchange) {
    Queue eventsQueue =
        QueueBuilder.durable(AmqpNames.QUEUE_EVENTS)
            .withArgument("x-dead-letter-exchange", AmqpNames.EXCHANGE_DLX)
            .withArgument("x-dead-letter-routing-key", AmqpNames.ROUTING_KEY_EVENTS_DLQ)
            .build();
    Binding personCreated = BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_PERSON_CREATED);
    Binding personActivated = BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_PERSON_ACTIVATED);
    Binding personDeactivated =
        BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_PERSON_DEACTIVATED);
    Binding studentNumberCorrected =
        BindingBuilder.bind(eventsQueue).to(eventsExchange).with(AmqpNames.ROUTING_KEY_STUDENT_NUMBER_CORRECTED);

    Queue dlq = QueueBuilder.durable(AmqpNames.QUEUE_EVENTS_DLQ).build();
    Binding dlqBinding = BindingBuilder.bind(dlq).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_EVENTS_DLQ);

    Queue retry5s = retryTierQueueTopic(AmqpNames.QUEUE_EVENTS_RETRY_5S, AmqpNames.RETRY_TTL_5S_MS, AmqpNames.QUEUE_EVENTS);
    Binding retry5sBinding = BindingBuilder.bind(retry5s).to(deadLetterExchange).with(AmqpNames.ROUTING_KEY_EVENTS_RETRY_5S);

    return new Declarables(
        eventsQueue, personCreated, personActivated, personDeactivated, studentNumberCorrected, dlq, dlqBinding,
        retry5s, retry5sBinding);
  }

  /**
   * {@code rpc.authentication}: transient, short TTL, no DLQ, no retry
   * tiers -- the ephemeral security RPC rules forbid all of that
   * (system_contract.md "RPC Security Rules").
   */
  @Bean
  public Declarables rpcTopology(DirectExchange securityRpcExchange) {
    Queue rpcQueue =
        QueueBuilder.durable(MessagingNames.AUTHENTICATION_RPC_QUEUE)
            .withArgument("x-message-ttl", AmqpNames.RPC_MESSAGE_TTL_MS)
            .build();
    Binding rpcBinding =
        BindingBuilder.bind(rpcQueue).to(securityRpcExchange).with(MessagingNames.AUTHENTICATION_RPC_QUEUE);
    return new Declarables(rpcQueue, rpcBinding);
  }

  /** A retry tier queue for a direct-exchange source: dead-letters back onto the same routing key it came from. */
  private static Queue retryTierQueue(String name, long ttlMillis, String deadLetterExchange, String deadLetterRoutingKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttlMillis)
        .withArgument("x-dead-letter-exchange", deadLetterExchange)
        .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey)
        .build();
  }

  /** A retry tier queue whose dead-letter republication must re-enter a queue directly, not via a topic pattern. */
  private static Queue retryTierQueueTopic(String name, long ttlMillis, String targetQueue) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttlMillis)
        .withArgument("x-dead-letter-exchange", "")
        .withArgument("x-dead-letter-routing-key", targetQueue)
        .build();
  }
}
