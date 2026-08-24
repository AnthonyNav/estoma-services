package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.infrastructure.config.PlatformProperties;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
public class RabbitConfig {
  @Bean
  @Primary
  RabbitTemplate requiredRouteRabbitTemplate(ConnectionFactory connectionFactory) {
    var template = new RabbitTemplate(connectionFactory);
    template.setMandatory(true);
    return template;
  }

  @Bean
  @Qualifier("securityRpcRabbitTemplate")
  RabbitTemplate securityRpcRabbitTemplate(
      ConnectionFactory connectionFactory, PlatformProperties properties) {
    var template = new RabbitTemplate(connectionFactory);
    template.setMandatory(true);
    template.setReplyTimeout(properties.securityRpc().timeout().toMillis());
    return template;
  }

  @Bean
  SimpleRabbitListenerContainerFactory resultsContainerFactory(
      ConnectionFactory connectionFactory,
      @Value("${platform.messaging.listeners-enabled:true}") boolean listenersEnabled) {
    var factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(true);
    factory.setAutoStartup(listenersEnabled);
    factory.setPrefetchCount(20);
    return factory;
  }
}
