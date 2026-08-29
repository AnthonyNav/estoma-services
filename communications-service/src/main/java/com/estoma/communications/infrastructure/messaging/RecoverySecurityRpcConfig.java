package com.estoma.communications.infrastructure.messaging;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RecoverySecurityRpcConfig {

  @Bean("recoverySecurityRpcTemplate")
  RabbitTemplate recoverySecurityRpcTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setObservationEnabled(true);
    template.setMandatory(true);
    template.setReplyTimeout(3_000L);
    return template;
  }
}
