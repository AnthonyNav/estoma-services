package com.estoma.users.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AMQP wiring shared by inbound (command consumption) and outbound
 * (result/event publishing, retry/DLQ routing) messaging.
 *
 * <p>Delivery semantics per docs/system-contract.md §7: AT_LEAST_ONCE,
 * publisher confirms on every publish, manual ACK only after the local
 * transaction commits. {@code spring.rabbitmq.publisher-confirm-type} and
 * {@code publisher-returns} (application.yml) make the auto-configured
 * {@code ConnectionFactory} enable confirms/returns even though this class
 * defines its own {@link RabbitTemplate} bean — those properties configure
 * the connection factory, not the template.
 */
@Configuration
public class RabbitConfig {

  private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

  @Bean
  public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    // Unroutable messages (e.g. a RESULT published before the BFF's
    // results.bff queue/binding exist -- see docs/system-contract.md §14
    // R1) must be observable, not silently dropped.
    template.setMandatory(true);
    template.setConfirmCallback(
        (correlationData, ack, cause) -> {
          if (!ack) {
            log.error(
                "publisher confirm NACK; correlationData={} cause={}",
                correlationData,
                cause);
          }
        });
    template.setReturnsCallback(
        returned ->
            log.error(
                "message returned as unroutable: exchange={} routingKey={} replyCode={} replyText={}",
                returned.getExchange(),
                returned.getRoutingKey(),
                returned.getReplyCode(),
                returned.getReplyText()));
    return template;
  }

  /**
   * Manual-ack container factory for {@code @RabbitListener} command
   * consumers (docs/system-contract.md §9): the listener ACKs only after
   * the dispatcher's transaction commits, never automatically.
   */
  @Bean
  public SimpleRabbitListenerContainerFactory manualAckContainerFactory(
      ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setPrefetchCount(10);
    factory.setConcurrentConsumers(2);
    factory.setMaxConcurrentConsumers(4);
    factory.setDefaultRequeueRejected(false);
    return factory;
  }
}
