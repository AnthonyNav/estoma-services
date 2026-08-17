package com.estoma.authorization.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("recoveryResultConsumer")
final class RecoveryResultConsumerHealthIndicator extends ListenerHealthIndicator {

  RecoveryResultConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "recoveryResultConsumer");
  }
}
