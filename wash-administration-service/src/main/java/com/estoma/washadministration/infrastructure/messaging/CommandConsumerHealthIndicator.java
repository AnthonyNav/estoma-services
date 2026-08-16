package com.estoma.washadministration.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("commandConsumer")
public class CommandConsumerHealthIndicator extends ListenerHealthIndicator {

  public CommandConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "commandConsumer");
  }
}
