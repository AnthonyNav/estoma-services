package com.estoma.academic.messaging;

import com.estoma.academic.application.AcademicQueryDispatcher;
import com.estoma.academic.contract.MessageEnvelope;
import com.rabbitmq.client.Channel;
import com.estoma.academic.observability.AcademicMessagingMetrics;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Handles side-effect-free recovery Queries and persists their Results before ACK. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class AcademicQueryListener {
  private static final Logger log = LoggerFactory.getLogger(AcademicQueryListener.class);

  private final EnvelopeCodec codec;
  private final AcademicQueryEnvelopeValidator validator;
  private final AcademicQueryDispatcher dispatcher;
  private final RetryPublisher retryPublisher;
  private final AcademicMessagingMetrics metrics;

  public AcademicQueryListener(
      EnvelopeCodec codec,
      AcademicQueryEnvelopeValidator validator,
      AcademicQueryDispatcher dispatcher,
      RetryPublisher retryPublisher,
      AcademicMessagingMetrics metrics) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.retryPublisher = retryPublisher;
    this.metrics = metrics;
  }

  @RabbitListener(id = "academicQueryListener", queues = AcademicTopology.QUERY_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope query;
    try {
      query = codec.decode(message.getBody());
    } catch (RuntimeException ex) {
      isolateInvalid(message, channel, tag, ex);
      return;
    }

    try {
      validator.validate(query);
    } catch (AcademicQueryEnvelopeValidator.RejectedException rejected) {
      try {
        dispatcher.reject(query, rejected.code());
        metrics.query();
        channel.basicAck(tag, false);
      } catch (RuntimeException persistenceFailure) {
        retryTechnical(message, channel, tag, query, persistenceFailure);
      }
      return;
    } catch (IllegalArgumentException invalid) {
      isolateInvalid(message, channel, tag, invalid);
      return;
    }

    try {
      dispatcher.dispatch(query);
      metrics.query();
      channel.basicAck(tag, false);
    } catch (RuntimeException ex) {
      retryTechnical(message, channel, tag, query, ex);
    }
  }

  private void isolateInvalid(Message message, Channel channel, long tag, RuntimeException ex) throws IOException {
    log.warn("invalid academic query messageId={} error={}",
        message.getMessageProperties().getMessageId(), ex.toString());
    if (retryPublisher.deadLetter(message, "academic.queries")) {
      channel.basicAck(tag, false);
    } else {
      channel.basicNack(tag, false, true);
    }
  }

  private void retryTechnical(
      Message message, Channel channel, long tag, MessageEnvelope query, RuntimeException ex) throws IOException {
    log.warn("academic query failed messageId={} error={}", query.messageId(), ex.toString());
    RetryPublisher.RetryOutcome outcome = retryPublisher.retry(message, "academic.queries.retry");
    if (outcome == RetryPublisher.RetryOutcome.PUBLISHED) {
      channel.basicAck(tag, false);
    } else if (outcome == RetryPublisher.RetryOutcome.EXHAUSTED) {
      channel.basicNack(tag, false, false);
    } else {
      channel.basicNack(tag, false, true);
    }
  }
}
