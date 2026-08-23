package com.estoma.washexecution.infrastructure.messaging;

import com.estoma.washexecution.contracts.MessagingNames;
import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AmqpTopologyConfig {
  @Bean DirectExchange commandsExchange() { return new DirectExchange(MessagingNames.COMMANDS_EXCHANGE, true, false); }
  @Bean DirectExchange queriesExchange() { return new DirectExchange(MessagingNames.QUERIES_EXCHANGE, true, false); }
  @Bean DirectExchange resultsExchange() { return new DirectExchange(MessagingNames.RESULTS_EXCHANGE, true, false); }
  @Bean TopicExchange eventsExchange() { return new TopicExchange(MessagingNames.EVENTS_EXCHANGE, true, false); }
  @Bean TopicExchange deadLetterExchange() { return new TopicExchange(MessagingNames.DLX_EXCHANGE, true, false); }

  @Bean
  Declarables washExecutionTopology(
      DirectExchange commandsExchange,
      DirectExchange queriesExchange,
      DirectExchange resultsExchange,
      TopicExchange eventsExchange,
      TopicExchange deadLetterExchange) {
    List<Declarable> values = new ArrayList<>();
    addDirect(values, MessagingNames.COMMAND_QUEUE, MessagingNames.COMMAND_RETRY_1,
        MessagingNames.COMMAND_RETRY_2, MessagingNames.COMMAND_DLQ,
        commandsExchange, MessagingNames.LOGICAL_COMPONENT_ID, deadLetterExchange);
    addDirect(values, MessagingNames.QUERY_QUEUE, MessagingNames.QUERY_RETRY_1,
        MessagingNames.QUERY_RETRY_2, MessagingNames.QUERY_DLQ,
        queriesExchange, MessagingNames.LOGICAL_COMPONENT_ID, deadLetterExchange);
    addDirect(values, MessagingNames.RESULT_QUEUE, MessagingNames.RESULT_RETRY_1,
        MessagingNames.RESULT_RETRY_2, MessagingNames.RESULT_DLQ,
        resultsExchange, MessagingNames.LOGICAL_COMPONENT_ID, deadLetterExchange);

    Queue eventMain = main(MessagingNames.EVENT_QUEUE, MessagingNames.EVENT_DLQ);
    values.add(eventMain);
    for (String key : MessagingNames.EVENT_BINDINGS) {
      values.add(BindingBuilder.bind(eventMain).to(eventsExchange).with(key));
    }
    addRetryAndDlq(values, MessagingNames.EVENT_QUEUE, MessagingNames.EVENT_RETRY_1,
        MessagingNames.EVENT_RETRY_2, MessagingNames.EVENT_DLQ, deadLetterExchange);
    return new Declarables(values);
  }

  private static void addDirect(
      List<Declarable> values,
      String mainName,
      String retry1,
      String retry2,
      String dlq,
      DirectExchange exchange,
      String routingKey,
      TopicExchange deadLetterExchange) {
    Queue main = main(mainName, dlq);
    values.add(main);
    values.add(BindingBuilder.bind(main).to(exchange).with(routingKey));
    addRetryAndDlq(values, mainName, retry1, retry2, dlq, deadLetterExchange);
  }

  private static void addRetryAndDlq(
      List<Declarable> values,
      String mainName,
      String retry1Name,
      String retry2Name,
      String dlqName,
      TopicExchange deadLetterExchange) {
    Queue retry1 = retry(retry1Name, AmqpNames.RETRY_1_TTL_MILLIS, mainName);
    Queue retry2 = retry(retry2Name, AmqpNames.RETRY_2_TTL_MILLIS, mainName);
    Queue dlq = QueueBuilder.durable(dlqName).build();
    values.add(retry1);
    values.add(BindingBuilder.bind(retry1).to(deadLetterExchange).with(retry1Name));
    values.add(retry2);
    values.add(BindingBuilder.bind(retry2).to(deadLetterExchange).with(retry2Name));
    values.add(dlq);
    values.add(BindingBuilder.bind(dlq).to(deadLetterExchange).with(dlqName));
  }

  private static Queue main(String name, String dlq) {
    return QueueBuilder.durable(name)
        .withArgument("x-dead-letter-exchange", MessagingNames.DLX_EXCHANGE)
        .withArgument("x-dead-letter-routing-key", dlq)
        .build();
  }

  private static Queue retry(String name, long ttl, String mainName) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttl)
        .withArgument("x-dead-letter-exchange", "")
        .withArgument("x-dead-letter-routing-key", mainName)
        .build();
  }
}
