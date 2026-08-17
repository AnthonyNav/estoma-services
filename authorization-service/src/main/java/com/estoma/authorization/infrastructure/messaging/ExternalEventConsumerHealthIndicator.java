package com.estoma.authorization.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("externalEventConsumer")
final class ExternalEventConsumerHealthIndicator extends ListenerHealthIndicator {

  ExternalEventConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "externalEventConsumer");
  }
}
