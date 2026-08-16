package com.estoma.washadministration.infrastructure.messaging;

import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.stereotype.Component;

@Component("queryConsumer")
public class QueryConsumerHealthIndicator extends ListenerHealthIndicator {

  public QueryConsumerHealthIndicator(RabbitListenerEndpointRegistry registry) {
    super(registry, "queryConsumer");
  }
}
