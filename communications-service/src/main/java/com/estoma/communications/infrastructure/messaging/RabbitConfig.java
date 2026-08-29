package com.estoma.communications.infrastructure.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

  @Bean("manualAckContainerFactory")
  SimpleRabbitListenerContainerFactory manualAckContainerFactory(ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setObservationEnabled(true);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(false);
    factory.setPrefetchCount(20);
    return factory;
  }

  @Bean
  RabbitTemplate communicationsRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setObservationEnabled(true);
    template.setMandatory(true);
    return template;
  }
}
