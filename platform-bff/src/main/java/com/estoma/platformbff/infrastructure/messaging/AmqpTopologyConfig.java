package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "platform.messaging", name = "topology-declare", havingValue = "true", matchIfMissing = true)
public class AmqpTopologyConfig {
  @Bean
  DirectExchange commandsExchange() {
    return new DirectExchange(MessagingTopology.COMMANDS_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange resultsExchange() {
    return new DirectExchange(MessagingTopology.RESULTS_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange queriesExchange() {
    return new DirectExchange(MessagingTopology.QUERIES_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange securityRpcExchange() {
    return new DirectExchange(MessagingTopology.SECURITY_RPC_EXCHANGE, true, false);
  }

  @Bean
  Declarables bffResultsTopology(DirectExchange resultsExchange) {
    var queue = QueueBuilder.durable(MessagingTopology.RESULTS_QUEUE).build();
    return new Declarables(queue,
        BindingBuilder.bind(queue).to(resultsExchange).with("bff"));
  }
}
