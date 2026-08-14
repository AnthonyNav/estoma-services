package com.estoma.academic.messaging;

import com.estoma.academic.application.StudentImportPersonResolutionResults;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Manual-ack entry point: the Inbox and validation work commit before RabbitMQ is acknowledged. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class PersonResolutionResultListener {
  private static final Logger log = LoggerFactory.getLogger(PersonResolutionResultListener.class);

  private final EnvelopeCodec codec;
  private final StudentImportPersonResolutionResults results;
  private final RetryPublisher retryPublisher;

  PersonResolutionResultListener(EnvelopeCodec codec, StudentImportPersonResolutionResults results, RetryPublisher retryPublisher) {
    this.codec = codec;
    this.results = results;
    this.retryPublisher = retryPublisher;
  }

  @RabbitListener(id = "academicPersonResolutionResultListener", queues = AcademicTopology.RESULT_QUEUE, containerFactory = "manualAckContainerFactory")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      results.apply(codec.decode(message.getBody()));
      channel.basicAck(tag, false);
    } catch (RuntimeException ex) {
      log.warn("academic person-resolution result failed messageId={} error={}", message.getMessageProperties().getMessageId(), ex.toString());
      if (retryPublisher.retry(message, "academic.results.retry")) channel.basicAck(tag, false);
      else channel.basicNack(tag, false, false);
    }
  }
}
