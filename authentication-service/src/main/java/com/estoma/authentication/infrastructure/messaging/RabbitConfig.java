package com.estoma.authentication.infrastructure.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * AMQP wiring shared by inbound (command/event consumption) and outbound
 * (Outbox relay, retry/DLQ routing) messaging. Publisher confirms and
 * returns are enabled on the connection factory via {@code
 * spring.rabbitmq.publisher-confirm-type}/{@code publisher-returns}
 * (application.yml) -- required for the confirm/return callbacks below to
 * fire at all (system_contract.md "Outbox Operational Policy").
 *
 * <p>Two templates, not one: a RESULT/retry/DLQ republication MUST be
 * routable (an unroutable one is a real failure), while an EVENT's
 * consumers own their own queues/bindings -- a still-missing binding must
 * not fail this service's Outbox. {@code requiredRouteRabbitTemplate} is
 * {@code @Primary} only so tests autowiring {@code RabbitTemplate} by type
 * keep working; the Outbox Relay always selects a template by explicit
 * qualifier.
 */
@Configuration
public class RabbitConfig {

  private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

  @Bean
  @Primary
  public RabbitTemplate requiredRouteRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMandatory(true);
    installObservabilityCallbacks(template);
    return template;
  }

  @Bean
  public RabbitTemplate eventRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    template.setMandatory(false);
    installObservabilityCallbacks(template);
    return template;
  }

  private static void installObservabilityCallbacks(RabbitTemplate template) {
    template.setConfirmCallback(
        (correlationData, ack, cause) -> {
          if (!ack) {
            log.error("publisher confirm NACK; correlationData={} cause={}", correlationData, cause);
          }
        });
    template.setReturnsCallback(
        returned ->
            log.error(
                "message returned as unroutable: exchange={} routingKey={} replyCode={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyCode(), returned.getReplyText()));
  }

  /**
   * Manual-ack container factory for durable command/event {@code
   * @RabbitListener}s: the listener ACKs only after the dispatcher's
   * transaction commits, never automatically (system_contract.md).
   */
  @Bean
  public SimpleRabbitListenerContainerFactory manualAckContainerFactory(ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setPrefetchCount(10);
    factory.setConcurrentConsumers(2);
    factory.setMaxConcurrentConsumers(4);
    factory.setDefaultRequeueRejected(false);
    return factory;
  }

  /**
   * Auto-ack factory for {@code rpc.authentication}: RPC Security Rules
   * (system_contract.md) forbid DLQ and late automatic retries for this
   * queue, so there is nothing a manual ack would protect -- a message
   * that fails to process just gets a rejection or timeout response.
   */
  @Bean
  public SimpleRabbitListenerContainerFactory rpcContainerFactory(ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
    factory.setPrefetchCount(20);
    factory.setConcurrentConsumers(2);
    factory.setMaxConcurrentConsumers(8);
    return factory;
  }
}
