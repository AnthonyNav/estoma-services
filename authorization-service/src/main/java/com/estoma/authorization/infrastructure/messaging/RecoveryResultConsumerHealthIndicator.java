package com.estoma.authorization.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component("recoveryResultConsumer")
@ConditionalOnProperty(
    prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
final class RecoveryResultConsumerHealthIndicator extends ListenerHealthIndicator {

  RecoveryResultConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "recoveryResultConsumer");
  }
}
