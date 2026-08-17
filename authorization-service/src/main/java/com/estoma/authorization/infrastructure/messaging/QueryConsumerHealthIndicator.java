package com.estoma.authorization.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("queryConsumer")
final class QueryConsumerHealthIndicator extends ListenerHealthIndicator {

  QueryConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "queryConsumer");
  }
}
