package com.estoma.authorization.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("queryConsumer")
@ConditionalOnProperty(
    prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
final class QueryConsumerHealthIndicator extends ListenerHealthIndicator {

  QueryConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "queryConsumer");
  }
}
