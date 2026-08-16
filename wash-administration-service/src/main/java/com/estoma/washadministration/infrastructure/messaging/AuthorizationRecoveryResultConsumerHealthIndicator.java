package com.estoma.washadministration.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("authorizationRecoveryResultConsumer")
public class AuthorizationRecoveryResultConsumerHealthIndicator extends ListenerHealthIndicator {

  public AuthorizationRecoveryResultConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "authorizationRecoveryResultConsumer");
  }
}
