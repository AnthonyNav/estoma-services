package com.estoma.academic.messaging;

import com.estoma.academic.config.AcademicOutboxProperties;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/** Republishes a technical failure to the broker retry tier only after a publisher confirm. */
@Component
class RetryPublisher {
  private final RabbitTemplate rabbit;
  private final AcademicOutboxProperties properties;
  RetryPublisher(@Qualifier("requiredRouteRabbitTemplate") RabbitTemplate rabbit, AcademicOutboxProperties properties) {
    this.rabbit = rabbit;
    this.properties = properties;
  }
  boolean retry(Message original, String route) {
    Object value = original.getMessageProperties().getHeaders().get("x-academic-retry");
    int attempts = value instanceof Number number ? number.intValue() : 0;
    if (attempts >= 3) return false;
    MessageProperties messageProperties = new MessageProperties();
    messageProperties.setContentType(original.getMessageProperties().getContentType());
    messageProperties.getHeaders().putAll(original.getMessageProperties().getHeaders());
    messageProperties.setHeader("x-academic-retry", attempts + 1);
    CorrelationData correlation = new CorrelationData();
    try {
      rabbit.send(AcademicTopology.DLX, route, new Message(original.getBody(), messageProperties), correlation);
      CorrelationData.Confirm confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      return confirm.ack() && correlation.getReturned() == null;
    } catch (Exception ex) { return false; }
  }
}
