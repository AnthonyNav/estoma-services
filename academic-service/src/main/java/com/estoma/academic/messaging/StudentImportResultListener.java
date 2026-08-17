package com.estoma.academic.messaging;

import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.observability.AcademicMessagingMetrics;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix="estoma.messaging",name="listeners-enabled",havingValue="true",matchIfMissing=true)
public class StudentImportResultListener {
  private static final Logger log = LoggerFactory.getLogger(StudentImportResultListener.class);

  private final EnvelopeCodec codec;
  private final StudentImportResultDispatcher dispatcher;
  private final RetryPublisher retryPublisher;
  private final AcademicMessagingMetrics metrics;

  StudentImportResultListener(
      EnvelopeCodec codec,
      StudentImportResultDispatcher dispatcher,
      RetryPublisher retryPublisher,
      AcademicMessagingMetrics metrics) {
    this.codec = codec;
    this.dispatcher = dispatcher;
    this.retryPublisher = retryPublisher;
    this.metrics = metrics;
  }

  @RabbitListener(id="academicResultListener",queues=AcademicTopology.RESULT_QUEUE,containerFactory="manualAckContainerFactory")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope result;
    try {
      result = codec.decode(message.getBody());
    } catch (RuntimeException ex) {
      log.warn("invalid academic result messageId={} error={}",
          message.getMessageProperties().getMessageId(), ex.toString());
      if (retryPublisher.deadLetter(message, "academic.results")) channel.basicAck(tag, false);
      else channel.basicNack(tag, false, true);
      return;
    }
    try {
      StudentImportResultDispatcher.Outcome outcome = dispatcher.dispatch(result);
      if (outcome == StudentImportResultDispatcher.Outcome.STALE) {
        metrics.staleResult();
        log.info("ignored stale academic result messageId={} type={}", result.messageId(), result.messageType());
      } else if (outcome == StudentImportResultDispatcher.Outcome.DUPLICATE) {
        metrics.duplicate();
      }
      channel.basicAck(tag, false);
    } catch (RuntimeException ex) {
      log.warn("academic import result failed messageId={} error={}",
          message.getMessageProperties().getMessageId(), ex.toString());
      if (ex instanceof IllegalArgumentException) {
        if (retryPublisher.deadLetter(message, "academic.results")) channel.basicAck(tag, false);
        else channel.basicNack(tag, false, true);
      } else {
        RetryPublisher.RetryOutcome outcome = retryPublisher.retry(message, "academic.results.retry");
        if (outcome == RetryPublisher.RetryOutcome.PUBLISHED) channel.basicAck(tag, false);
        else if (outcome == RetryPublisher.RetryOutcome.EXHAUSTED) channel.basicNack(tag, false, false);
        else channel.basicNack(tag, false, true);
      }
    }
  }
}
