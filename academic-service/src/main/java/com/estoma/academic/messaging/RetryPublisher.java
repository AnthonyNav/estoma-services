package com.estoma.academic.messaging;

import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** Republishes a technical failure to the broker retry tier only after a publisher confirm. */
@Component
class RetryPublisher {
  private final RabbitTemplate rabbit;
  RetryPublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }
  boolean retry(Message original, String route) {
    Object value = original.getMessageProperties().getHeaders().get("x-academic-retry");
    int attempts = value instanceof Number number ? number.intValue() : 0;
    if (attempts >= 3) return false;
    MessageProperties properties = new MessageProperties();
    properties.setContentType(original.getMessageProperties().getContentType());
    properties.getHeaders().putAll(original.getMessageProperties().getHeaders());
    properties.setHeader("x-academic-retry", attempts + 1);
    CorrelationData correlation = new CorrelationData();
    try {
      rabbit.send(AcademicTopology.DLX, route, new Message(original.getBody(), properties), correlation);
      return correlation.getFuture().get(5, TimeUnit.SECONDS).isAck();
    } catch (Exception ex) { return false; }
  }
}
