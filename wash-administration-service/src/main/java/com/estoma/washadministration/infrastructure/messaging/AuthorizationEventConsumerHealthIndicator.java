package com.estoma.washadministration.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("authorizationEventConsumer")
public class AuthorizationEventConsumerHealthIndicator extends ListenerHealthIndicator {

  public AuthorizationEventConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "authorizationEventConsumer");
  }
}
