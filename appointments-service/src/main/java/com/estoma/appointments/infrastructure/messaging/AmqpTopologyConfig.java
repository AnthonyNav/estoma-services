package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessagingNames;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
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
public class AmqpTopologyConfig {
  @Bean DirectExchange appointmentsCommandsExchange() { return new DirectExchange(MessagingNames.COMMANDS_EXCHANGE, true, false); }
  @Bean DirectExchange appointmentsQueriesExchange() { return new DirectExchange(MessagingNames.QUERIES_EXCHANGE, true, false); }
  @Bean DirectExchange appointmentsResultsExchange() { return new DirectExchange(MessagingNames.RESULTS_EXCHANGE, true, false); }
  @Bean TopicExchange appointmentsEventsExchange() { return new TopicExchange(MessagingNames.EVENTS_EXCHANGE, true, false); }
  @Bean TopicExchange appointmentsDlxExchange() { return new TopicExchange(MessagingNames.DLX_EXCHANGE, true, false); }

  @Bean Declarables appointmentsCommandTopology(DirectExchange appointmentsCommandsExchange,
      TopicExchange appointmentsDlxExchange, MessagingProperties properties) {
    Queue main = main(MessagingNames.COMMAND_QUEUE, MessagingNames.COMMAND_DLQ);
    Queue retry1 = retry(MessagingNames.COMMAND_RETRY_1, properties.retry1Ttl().toMillis(),
        MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID);
    Queue retry2 = retry(MessagingNames.COMMAND_RETRY_2, properties.retry2Ttl().toMillis(),
        MessagingNames.COMMANDS_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID);
    Queue dlq = QueueBuilder.durable(MessagingNames.COMMAND_DLQ).build();
    return new Declarables(main, BindingBuilder.bind(main).to(appointmentsCommandsExchange).with(MessagingNames.LOGICAL_COMPONENT_ID),
        retry1, BindingBuilder.bind(retry1).to(appointmentsDlxExchange).with(MessagingNames.COMMAND_RETRY_1),
        retry2, BindingBuilder.bind(retry2).to(appointmentsDlxExchange).with(MessagingNames.COMMAND_RETRY_2),
        dlq, BindingBuilder.bind(dlq).to(appointmentsDlxExchange).with(MessagingNames.COMMAND_DLQ));
  }

  @Bean Declarables appointmentsQueryTopology(DirectExchange appointmentsQueriesExchange,
      TopicExchange appointmentsDlxExchange, MessagingProperties properties) {
    Queue main = main(MessagingNames.QUERY_QUEUE, MessagingNames.QUERY_DLQ);
    Queue retry1 = retry(MessagingNames.QUERY_RETRY_1, properties.retry1Ttl().toMillis(),
        MessagingNames.QUERIES_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID);
    Queue retry2 = retry(MessagingNames.QUERY_RETRY_2, properties.retry2Ttl().toMillis(),
        MessagingNames.QUERIES_EXCHANGE, MessagingNames.LOGICAL_COMPONENT_ID);
    Queue dlq = QueueBuilder.durable(MessagingNames.QUERY_DLQ).build();
    return new Declarables(main, BindingBuilder.bind(main).to(appointmentsQueriesExchange).with(MessagingNames.LOGICAL_COMPONENT_ID),
        retry1, BindingBuilder.bind(retry1).to(appointmentsDlxExchange).with(MessagingNames.QUERY_RETRY_1),
        retry2, BindingBuilder.bind(retry2).to(appointmentsDlxExchange).with(MessagingNames.QUERY_RETRY_2),
        dlq, BindingBuilder.bind(dlq).to(appointmentsDlxExchange).with(MessagingNames.QUERY_DLQ));
  }

  @Bean Declarables appointmentsEventTopology(TopicExchange appointmentsEventsExchange,
      TopicExchange appointmentsDlxExchange, MessagingProperties properties) {
    Queue main = main(MessagingNames.EVENT_QUEUE, MessagingNames.EVENT_DLQ);
    Queue retry1 = localRetry(MessagingNames.EVENT_RETRY_1, properties.retry1Ttl().toMillis(), MessagingNames.EVENT_QUEUE);
    Queue retry2 = localRetry(MessagingNames.EVENT_RETRY_2, properties.retry2Ttl().toMillis(), MessagingNames.EVENT_QUEUE);
    Queue dlq = QueueBuilder.durable(MessagingNames.EVENT_DLQ).build();
    List<Declarable> values = new ArrayList<>();
    values.add(main);
    values.add(BindingBuilder.bind(main).to(appointmentsEventsExchange).with("academic.#"));
    values.add(BindingBuilder.bind(main).to(appointmentsEventsExchange).with("authorization.#"));
    values.add(BindingBuilder.bind(main).to(appointmentsEventsExchange).with("wash-administration.#"));
    MessagingNames.WASH_EXECUTION_EVENT_ROUTES.values().stream().sorted()
        .map(key -> BindingBuilder.bind(main).to(appointmentsEventsExchange).with(key))
        .forEach(values::add);
    values.add(retry1);
    values.add(BindingBuilder.bind(retry1).to(appointmentsDlxExchange).with(MessagingNames.EVENT_RETRY_1));
    values.add(retry2);
    values.add(BindingBuilder.bind(retry2).to(appointmentsDlxExchange).with(MessagingNames.EVENT_RETRY_2));
    values.add(dlq);
    values.add(BindingBuilder.bind(dlq).to(appointmentsDlxExchange).with(MessagingNames.EVENT_DLQ));
    return new Declarables(values);
  }

  @Bean Declarables appointmentsResultTopology(DirectExchange appointmentsResultsExchange,
      TopicExchange appointmentsDlxExchange, MessagingProperties properties) {
    Queue main = main(MessagingNames.RESULT_QUEUE, MessagingNames.RESULT_DLQ);
    Queue retry1 = localRetry(MessagingNames.RESULT_RETRY_1, properties.retry1Ttl().toMillis(), MessagingNames.RESULT_QUEUE);
    Queue retry2 = localRetry(MessagingNames.RESULT_RETRY_2, properties.retry2Ttl().toMillis(), MessagingNames.RESULT_QUEUE);
    Queue dlq = QueueBuilder.durable(MessagingNames.RESULT_DLQ).build();
    return new Declarables(main, BindingBuilder.bind(main).to(appointmentsResultsExchange).with(MessagingNames.LOGICAL_COMPONENT_ID),
        retry1, BindingBuilder.bind(retry1).to(appointmentsDlxExchange).with(MessagingNames.RESULT_RETRY_1),
        retry2, BindingBuilder.bind(retry2).to(appointmentsDlxExchange).with(MessagingNames.RESULT_RETRY_2),
        dlq, BindingBuilder.bind(dlq).to(appointmentsDlxExchange).with(MessagingNames.RESULT_DLQ));
  }

  private static Queue main(String name, String dlq) {
    return QueueBuilder.durable(name).withArgument("x-dead-letter-exchange", MessagingNames.DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", dlq).build();
  }

  private static Queue retry(String name, long ttl, String exchange, String key) {
    return QueueBuilder.durable(name).withArgument("x-message-ttl", ttl)
        .withArgument("x-dead-letter-exchange", exchange).withArgument("x-dead-letter-routing-key", key).build();
  }

  private static Queue localRetry(String name, long ttl, String returnQueue) {
    return QueueBuilder.durable(name).withArgument("x-message-ttl", ttl)
        .withArgument("x-dead-letter-exchange", "").withArgument("x-dead-letter-routing-key", returnQueue).build();
  }
}
