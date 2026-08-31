package com.estoma.authorization.infrastructure.messaging;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
class RabbitConfig {

  @Bean
  @Primary
  RabbitTemplate requiredRouteRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setObservationEnabled(true);
    template.setMandatory(true);
    return template;
  }

  @Bean
  RabbitTemplate eventRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setObservationEnabled(true);
    template.setMandatory(false);
    return template;
  }

  @Bean
  SimpleRabbitListenerContainerFactory manualAckContainerFactory(
      ConnectionFactory connectionFactory,
      @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setObservationEnabled(true);
    factory.setAutoStartup(autoStartup);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(false);
    factory.setPrefetchCount(10);
    factory.setConcurrentConsumers(2);
    factory.setMaxConcurrentConsumers(4);
    return factory;
  }
}
