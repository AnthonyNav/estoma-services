package com.estoma.users.messaging;

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
 * AMQP wiring shared by inbound (command consumption) and outbound
 * (result/event publishing, retry/DLQ routing) messaging.
 *
 * <p>Delivery semantics per docs/system-contract.md §7: AT_LEAST_ONCE,
 * publisher confirms on every publish, manual ACK only after the local
 * transaction commits. {@code spring.rabbitmq.publisher-confirm-type} and
 * {@code publisher-returns} (application.yml) make the auto-configured
 * {@code ConnectionFactory} enable confirms/returns even though this class
 * defines its own {@link RabbitTemplate} beans — those properties configure
 * the connection factory, not the templates.
 *
 * <p>Two templates, not one with a global {@code mandatory} flag
 * (docs/system-contract.md §7, §9): RESULT/retry/DLQ MUST be routable —
 * an unroutable RESULT or a lost retry/DLQ republication is a real
 * failure. EVENT consumers own their own queues/bindings; a still-missing
 * binding must not fail this service's Outbox, so events are published
 * with {@code mandatory=false}. {@code requiredRouteRabbitTemplate} is
 * {@code @Primary} purely so existing tests that autowire {@code
 * RabbitTemplate} by type keep working (they all publish to routable
 * destinations) — the productive publishing path
 * ({@code DefaultConfirmedRabbitPublisher}) never relies on that and
 * always selects a template by explicit qualifier. The success/failure
 * decision for a publication lives exclusively in {@code
 * ConfirmedRabbitPublisher} — these confirm/return callbacks exist only
 * for observability logging.
 */
@Configuration
public class RabbitConfig {

  private static final Logger log = LoggerFactory.getLogger(RabbitConfig.class);

  @Bean
  @Primary
  public RabbitTemplate requiredRouteRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    // RESULT, retry tiers and DLQ must always land in a queue
    // (docs/system-contract.md §14 R1); an unroutable message here is a
    // real failure that ConfirmedRabbitPublisher classifies as
    // PUBLISH_RETURNED.
    template.setMandatory(true);
    installObservabilityCallbacks(template);
    return template;
  }

  @Bean
  public RabbitTemplate eventRabbitTemplate(ConnectionFactory connectionFactory) {
    RabbitTemplate template = new RabbitTemplate(connectionFactory);
    // A missing binding on platform.events must not fail the Outbox --
    // only the publisher confirm is required for an EVENT to count as
    // published (docs/system-contract.md §7, §9).
    template.setMandatory(false);
    installObservabilityCallbacks(template);
    return template;
  }

  private static void installObservabilityCallbacks(RabbitTemplate template) {
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
