package com.estoma.authorization.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("commandConsumer")
final class CommandConsumerHealthIndicator extends ListenerHealthIndicator {

  CommandConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "commandConsumer");
  }
}
