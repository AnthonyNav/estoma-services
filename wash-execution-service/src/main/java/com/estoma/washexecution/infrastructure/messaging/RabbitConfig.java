package com.estoma.washexecution.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitConfig {
  private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

  @Bean
  @Primary
  RabbitTemplate requiredRouteRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMandatory(true);
    installCallbacks(template);
    return template;
  }

  @Bean
  RabbitTemplate eventRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMandatory(false);
    installCallbacks(template);
    return template;
  }

  @Bean
  SimpleRabbitListenerContainerFactory manualAckContainerFactory(
      ConnectionFactory connectionFactory,
      @Value("${spring.rabbitmq.listener.simple.auto-startup:true}") boolean autoStartup) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAutoStartup(autoStartup);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(false);
    factory.setPrefetchCount(10);
    factory.setConcurrentConsumers(2);
    factory.setMaxConcurrentConsumers(4);
    return factory;
  }

  private static void installCallbacks(RabbitTemplate template) {
    template.setConfirmCallback((data, ack, cause) -> {
      if (!ack) log.error("publisher confirm NACK data={} cause={}", data, cause);
    });
    template.setReturnsCallback(returned -> log.error(
        "unroutable message exchange={} routingKey={} replyCode={} replyText={}",
        returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
  }
}
