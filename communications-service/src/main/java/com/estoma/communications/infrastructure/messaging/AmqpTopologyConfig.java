package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.contracts.MessagingNames;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
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

  @Bean DirectExchange communicationsCommandsExchange() { return new DirectExchange(MessagingNames.COMMANDS_EXCHANGE, true, false); }
  @Bean DirectExchange communicationsResultsExchange() { return new DirectExchange(MessagingNames.RESULTS_EXCHANGE, true, false); }
  @Bean TopicExchange communicationsEventsExchange() { return new TopicExchange(MessagingNames.EVENTS_EXCHANGE, true, false); }
  @Bean DirectExchange communicationsDlxExchange() { return new DirectExchange(MessagingNames.DLX_EXCHANGE, true, false); }

  @Bean Queue communicationsCommandQueue() { return QueueBuilder.durable(MessagingNames.COMMAND_QUEUE).build(); }
  @Bean Binding communicationsCommandBinding(Queue communicationsCommandQueue, DirectExchange communicationsCommandsExchange) {
    return BindingBuilder.bind(communicationsCommandQueue).to(communicationsCommandsExchange).with("communications");
  }

  @Bean Queue communicationsEventQueue() { return QueueBuilder.durable(MessagingNames.EVENT_QUEUE).build(); }
  @Bean Binding communicationsUsersPersonBinding(Queue communicationsEventQueue, TopicExchange communicationsEventsExchange) {
    return BindingBuilder.bind(communicationsEventQueue).to(communicationsEventsExchange).with("users.person.*");
  }
  @Bean Binding communicationsAuthenticationAccountBinding(Queue communicationsEventQueue, TopicExchange communicationsEventsExchange) {
    return BindingBuilder.bind(communicationsEventQueue).to(communicationsEventsExchange).with("authentication.account.*");
  }
  @Bean Binding communicationsAppointmentBinding(Queue communicationsEventQueue, TopicExchange communicationsEventsExchange) {
    return BindingBuilder.bind(communicationsEventQueue).to(communicationsEventsExchange).with("appointments.appointment.cancelled");
  }
  @Bean Binding communicationsEventRetryReturnBinding(Queue communicationsEventQueue, DirectExchange communicationsDlxExchange) {
    return BindingBuilder.bind(communicationsEventQueue).to(communicationsDlxExchange).with(MessagingNames.EVENT_RETRY_RETURN);
  }

  @Bean Queue communicationsCommandRetry5s() { return retry(MessagingNames.COMMAND_RETRY_5S, 5_000, MessagingNames.COMMANDS_EXCHANGE, "communications"); }
  @Bean Queue communicationsCommandRetry30s() { return retry(MessagingNames.COMMAND_RETRY_30S, 30_000, MessagingNames.COMMANDS_EXCHANGE, "communications"); }
  @Bean Queue communicationsCommandRetry300s() { return retry(MessagingNames.COMMAND_RETRY_300S, 300_000, MessagingNames.COMMANDS_EXCHANGE, "communications"); }
  @Bean Queue communicationsCommandDlq() { return QueueBuilder.durable(MessagingNames.COMMAND_DLQ).build(); }

  @Bean Queue communicationsEventRetry5s() { return retry(MessagingNames.EVENT_RETRY_5S, 5_000, MessagingNames.DLX_EXCHANGE, MessagingNames.EVENT_RETRY_RETURN); }
  @Bean Queue communicationsEventRetry30s() { return retry(MessagingNames.EVENT_RETRY_30S, 30_000, MessagingNames.DLX_EXCHANGE, MessagingNames.EVENT_RETRY_RETURN); }
  @Bean Queue communicationsEventRetry300s() { return retry(MessagingNames.EVENT_RETRY_300S, 300_000, MessagingNames.DLX_EXCHANGE, MessagingNames.EVENT_RETRY_RETURN); }
  @Bean Queue communicationsEventDlq() { return QueueBuilder.durable(MessagingNames.EVENT_DLQ).build(); }

  @Bean Binding commandRetry5sBinding(Queue communicationsCommandRetry5s, DirectExchange communicationsDlxExchange) { return BindingBuilder.bind(communicationsCommandRetry5s).to(communicationsDlxExchange).with(MessagingNames.COMMAND_RETRY_5S); }
  @Bean Binding commandRetry30sBinding(Queue communicationsCommandRetry30s, DirectExchange communicationsDlxExchange) { return BindingBuilder.bind(communicationsCommandRetry30s).to(communicationsDlxExchange).with(MessagingNames.COMMAND_RETRY_30S); }
  @Bean Binding commandRetry300sBinding(Queue communicationsCommandRetry300s, DirectExchange communicationsDlxExchange) { return BindingBuilder.bind(communicationsCommandRetry300s).to(communicationsDlxExchange).with(MessagingNames.COMMAND_RETRY_300S); }
  @Bean Binding commandDlqBinding(Queue communicationsCommandDlq, DirectExchange communicationsDlxExchange) { return BindingBuilder.bind(communicationsCommandDlq).to(communicationsDlxExchange).with(MessagingNames.COMMAND_DLQ); }

  @Bean Binding eventRetry5sBinding(Queue communicationsEventRetry5s, DirectExchange communicationsDlxExchange) { return BindingBuilder.bind(communicationsEventRetry5s).to(communicationsDlxExchange).with(MessagingNames.EVENT_RETRY_5S); }
  @Bean Binding eventRetry30sBinding(Queue communicationsEventRetry30s, DirectExchange communicationsDlxExchange) { return BindingBuilder.bind(communicationsEventRetry30s).to(communicationsDlxExchange).with(MessagingNames.EVENT_RETRY_30S); }
  @Bean Binding eventRetry300sBinding(Queue communicationsEventRetry300s, DirectExchange communicationsDlxExchange) { return BindingBuilder.bind(communicationsEventRetry300s).to(communicationsDlxExchange).with(MessagingNames.EVENT_RETRY_300S); }
  @Bean Binding eventDlqBinding(Queue communicationsEventDlq, DirectExchange communicationsDlxExchange) { return BindingBuilder.bind(communicationsEventDlq).to(communicationsDlxExchange).with(MessagingNames.EVENT_DLQ); }

  private static Queue retry(String name, int ttlMs, String deadLetterExchange, String deadLetterRoutingKey) {
    return QueueBuilder.durable(name)
        .withArgument("x-message-ttl", ttlMs)
        .withArgument("x-dead-letter-exchange", deadLetterExchange)
        .withArgument("x-dead-letter-routing-key", deadLetterRoutingKey)
        .build();
  }
}
