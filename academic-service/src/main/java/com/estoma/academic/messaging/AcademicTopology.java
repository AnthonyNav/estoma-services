package com.estoma.academic.messaging;

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
@ConditionalOnProperty(prefix = "estoma.messaging", name = "topology-declare", havingValue = "true", matchIfMissing = true)
public class AcademicTopology {
  public static final String COMMANDS = "platform.commands";
  public static final String QUERIES = "platform.queries";
  public static final String RESULTS = "platform.results";
  public static final String EVENTS = "platform.events";
  public static final String COMMAND_QUEUE = "commands.academic";
  public static final String QUERY_QUEUE = "queries.academic";
  public static final String RESULT_QUEUE = "results.academic";
  public static final String EVENT_QUEUE = "events.academic";
  public static final String DLX = "platform.dlx";

  private static final long RETRY_1_TTL_MS = 5_000L;
  private static final long RETRY_2_TTL_MS = 30_000L;

  @Bean DirectExchange academicCommandsExchange() { return new DirectExchange(COMMANDS, true, false); }
  @Bean DirectExchange academicQueriesExchange() { return new DirectExchange(QUERIES, true, false); }
  @Bean DirectExchange academicResultsExchange() { return new DirectExchange(RESULTS, true, false); }
  @Bean TopicExchange academicEventsExchange() { return new TopicExchange(EVENTS, true, false); }
  @Bean TopicExchange academicDeadLetterExchange() { return new TopicExchange(DLX, true, false); }

  @Bean
  Declarables academicQueues(
      DirectExchange academicCommandsExchange,
      DirectExchange academicQueriesExchange,
      DirectExchange academicResultsExchange,
      TopicExchange academicEventsExchange,
      TopicExchange academicDeadLetterExchange) {
    var commands = mainQueue(COMMAND_QUEUE, "academic.commands");
    var queries = mainQueue(QUERY_QUEUE, "academic.queries");
    var results = mainQueue(RESULT_QUEUE, "academic.results");
    var events = mainQueue(EVENT_QUEUE, "academic.events");

    var commandDlq = QueueBuilder.durable(COMMAND_QUEUE + ".dlq").build();
    var queryDlq = QueueBuilder.durable(QUERY_QUEUE + ".dlq").build();
    var resultDlq = QueueBuilder.durable(RESULT_QUEUE + ".dlq").build();
    var eventDlq = QueueBuilder.durable(EVENT_QUEUE + ".dlq").build();

    var commandRetry1 = retryQueue(COMMAND_QUEUE + ".retry.1", RETRY_1_TTL_MS, COMMANDS, "academic");
    var commandRetry2 = retryQueue(COMMAND_QUEUE + ".retry.2", RETRY_2_TTL_MS, COMMANDS, "academic");
    var queryRetry1 = retryQueue(QUERY_QUEUE + ".retry.1", RETRY_1_TTL_MS, QUERIES, "academic");
    var queryRetry2 = retryQueue(QUERY_QUEUE + ".retry.2", RETRY_2_TTL_MS, QUERIES, "academic");
    var resultRetry1 = retryQueue(RESULT_QUEUE + ".retry.1", RETRY_1_TTL_MS, RESULTS, "academic");
    var resultRetry2 = retryQueue(RESULT_QUEUE + ".retry.2", RETRY_2_TTL_MS, RESULTS, "academic");
    // Events have multiple external routing keys. Retry uses one private internal key back to this consumer queue.
    var eventRetry1 = retryQueue(EVENT_QUEUE + ".retry.1", RETRY_1_TTL_MS, EVENTS, "academic.retry");
    var eventRetry2 = retryQueue(EVENT_QUEUE + ".retry.2", RETRY_2_TTL_MS, EVENTS, "academic.retry");

    return new Declarables(
        commands, BindingBuilder.bind(commands).to(academicCommandsExchange).with("academic"),
        queries, BindingBuilder.bind(queries).to(academicQueriesExchange).with("academic"),
        results, BindingBuilder.bind(results).to(academicResultsExchange).with("academic"),
        events,
        BindingBuilder.bind(events).to(academicEventsExchange).with("users.person.created"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("users.person.updated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("users.person.activated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("users.person.deactivated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authentication.account.created"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authentication.account.activated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authentication.account.deactivated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authorization.account-role.assigned"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authorization.account-role.changed"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authorization.system-access.granted"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authorization.system-access.suspended"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authorization.system-access.revoked"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("authorization.system-access.restored"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("academic.retry"),
        commandDlq, BindingBuilder.bind(commandDlq).to(academicDeadLetterExchange).with("academic.commands"),
        queryDlq, BindingBuilder.bind(queryDlq).to(academicDeadLetterExchange).with("academic.queries"),
        resultDlq, BindingBuilder.bind(resultDlq).to(academicDeadLetterExchange).with("academic.results"),
        eventDlq, BindingBuilder.bind(eventDlq).to(academicDeadLetterExchange).with("academic.events"),
        commandRetry1, BindingBuilder.bind(commandRetry1).to(academicDeadLetterExchange).with("academic.commands.retry.1"),
        commandRetry2, BindingBuilder.bind(commandRetry2).to(academicDeadLetterExchange).with("academic.commands.retry.2"),
        queryRetry1, BindingBuilder.bind(queryRetry1).to(academicDeadLetterExchange).with("academic.queries.retry.1"),
        queryRetry2, BindingBuilder.bind(queryRetry2).to(academicDeadLetterExchange).with("academic.queries.retry.2"),
        resultRetry1, BindingBuilder.bind(resultRetry1).to(academicDeadLetterExchange).with("academic.results.retry.1"),
        resultRetry2, BindingBuilder.bind(resultRetry2).to(academicDeadLetterExchange).with("academic.results.retry.2"),
        eventRetry1, BindingBuilder.bind(eventRetry1).to(academicDeadLetterExchange).with("academic.events.retry.1"),
        eventRetry2, BindingBuilder.bind(eventRetry2).to(academicDeadLetterExchange).with("academic.events.retry.2"));
  }

  private static Queue mainQueue(String name, String deadLetterRoutingKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-dead-letter-exchange", DLX)
        .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey)
        .build();
  }

  private static Queue retryQueue(String name, long ttlMillis, String exchange, String routingKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttlMillis)
        .withArgument("x-dead-letter-exchange", exchange)
        .withArgument("x-dead-letter-routing-key", routingKey)
        .build();
  }
}
