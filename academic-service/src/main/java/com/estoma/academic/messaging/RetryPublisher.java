package com.estoma.academic.messaging;

import com.estoma.academic.config.AcademicOutboxProperties;
import java.util.concurrent.TimeUnit;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Republishes a technical failure to the next broker retry tier only after a publisher confirm. */
@Component
class RetryPublisher {
  private static final String RETRY_HEADER = "x-academic-retry";

  private final RabbitTemplate rabbit;
  private final AcademicOutboxProperties properties;
  private final int maxAttempts;

  RetryPublisher(
      @Qualifier("requiredRouteRabbitTemplate") RabbitTemplate rabbit,
      AcademicOutboxProperties properties,
      @Value("${estoma.messaging.retry-max-attempts:3}") int maxAttempts) {
    this.rabbit = rabbit;
    this.properties = properties;
    if (maxAttempts < 1 || maxAttempts > 3) {
      throw new IllegalArgumentException("retry-max-attempts must be between 1 and 3 in V1");
    }
    this.maxAttempts = maxAttempts;
  }

  RetryOutcome retry(Message original, String routePrefix) {
    Object value = original.getMessageProperties().getHeaders().get(RETRY_HEADER);
    int completedRetries = value instanceof Number number ? number.intValue() : 0;
    // maxAttempts counts the initial delivery. With V1 maxAttempts=3, only retry.1 and retry.2 exist.
    if (completedRetries >= maxAttempts - 1) {
      return RetryOutcome.EXHAUSTED;
    }

    int nextRetry = completedRetries + 1;
    MessageProperties messageProperties = copyProperties(original);
    messageProperties.setHeader(RETRY_HEADER, nextRetry);
    CorrelationData correlation = new CorrelationData();
    try {
      rabbit.send(
          AcademicTopology.DLX,
          routePrefix + "." + nextRetry,
          new Message(original.getBody(), messageProperties),
          correlation);
      CorrelationData.Confirm confirm = correlation.getFuture()
          .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      return confirm.ack() && correlation.getReturned() == null
          ? RetryOutcome.PUBLISHED
          : RetryOutcome.PUBLISH_FAILED;
    } catch (Exception ex) {
      return RetryOutcome.PUBLISH_FAILED;
    }
  }

  boolean deadLetter(Message original, String route) {
    MessageProperties messageProperties = copyProperties(original);
    CorrelationData correlation = new CorrelationData();
    try {
      rabbit.send(
          AcademicTopology.DLX,
          route,
          new Message(original.getBody(), messageProperties),
          correlation);
      CorrelationData.Confirm confirm = correlation.getFuture()
          .get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
      return confirm.ack() && correlation.getReturned() == null;
    } catch (Exception ex) {
      return false;
    }
  }

  private static MessageProperties copyProperties(Message original) {
    MessageProperties messageProperties = new MessageProperties();
    messageProperties.setContentType(original.getMessageProperties().getContentType());
    messageProperties.setMessageId(original.getMessageProperties().getMessageId());
    messageProperties.getHeaders().putAll(original.getMessageProperties().getHeaders());
    return messageProperties;
  }

  enum RetryOutcome { PUBLISHED, EXHAUSTED, PUBLISH_FAILED }
}
