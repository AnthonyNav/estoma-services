package com.estoma.academic.messaging;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "estoma.messaging", name = "topology-declare", havingValue = "true", matchIfMissing = true)
public class AcademicTopology {
  public static final String COMMANDS = "platform.commands";
  public static final String RESULTS = "platform.results";
  public static final String EVENTS = "platform.events";
  public static final String COMMAND_QUEUE = "commands.academic";
  public static final String EVENT_QUEUE = "events.academic";
  public static final String DLX = "platform.dlx";

  @Bean DirectExchange academicCommandsExchange() { return new DirectExchange(COMMANDS, true, false); }
  @Bean DirectExchange academicResultsExchange() { return new DirectExchange(RESULTS, true, false); }
  @Bean TopicExchange academicEventsExchange() { return new TopicExchange(EVENTS, true, false); }
  @Bean TopicExchange academicDeadLetterExchange() { return new TopicExchange(DLX, true, false); }
  @Bean Declarables academicQueues(DirectExchange academicCommandsExchange, TopicExchange academicEventsExchange, TopicExchange academicDeadLetterExchange) {
    var commands = QueueBuilder.durable(COMMAND_QUEUE).withArgument("x-dead-letter-exchange", DLX).withArgument("x-dead-letter-routing-key", "academic.commands").build();
    var events = QueueBuilder.durable(EVENT_QUEUE).withArgument("x-dead-letter-exchange", DLX).withArgument("x-dead-letter-routing-key", "academic.events").build();
    var commandDlq = QueueBuilder.durable("commands.academic.dlq").build();
    var eventDlq = QueueBuilder.durable("events.academic.dlq").build();
    var commandRetry = QueueBuilder.durable("commands.academic.retry.5s").withArgument("x-message-ttl", 5000).withArgument("x-dead-letter-exchange", COMMANDS).withArgument("x-dead-letter-routing-key", "academic").build();
    var eventRetry = QueueBuilder.durable("events.academic.retry.5s").withArgument("x-message-ttl", 5000).withArgument("x-dead-letter-exchange", EVENTS).withArgument("x-dead-letter-routing-key", "academic.retry").build();
    return new Declarables(commands, BindingBuilder.bind(commands).to(academicCommandsExchange).with("academic"),
        events,
        BindingBuilder.bind(events).to(academicEventsExchange).with("users.person.created"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("users.person.updated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("users.person.activated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("users.person.deactivated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("identity.person.created"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("identity.person.updated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("identity.person.activated"),
        BindingBuilder.bind(events).to(academicEventsExchange).with("identity.person.deactivated"),
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
        eventDlq, BindingBuilder.bind(eventDlq).to(academicDeadLetterExchange).with("academic.events"),
        commandRetry, BindingBuilder.bind(commandRetry).to(academicDeadLetterExchange).with("academic.commands.retry"),
        eventRetry, BindingBuilder.bind(eventRetry).to(academicDeadLetterExchange).with("academic.events.retry"));
  }
}
