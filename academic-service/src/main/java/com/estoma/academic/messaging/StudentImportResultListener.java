package com.estoma.academic.messaging;

import com.estoma.academic.application.StudentImportApplicationManager;
import com.estoma.academic.application.StudentImportPersonResolutionResults;
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
  private final StudentImportApplicationManager applications;
  private final StudentImportPersonResolutionResults resolutions;
  private final RetryPublisher retryPublisher;

  StudentImportResultListener(
      EnvelopeCodec codec,
      StudentImportApplicationManager applications,
      StudentImportPersonResolutionResults resolutions,
      RetryPublisher retryPublisher) {
    this.codec = codec;
    this.applications = applications;
    this.resolutions = resolutions;
    this.retryPublisher = retryPublisher;
  }

  @RabbitListener(id="academicResultListener",queues=AcademicTopology.RESULT_QUEUE,containerFactory="manualAckContainerFactory")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      var result = codec.decode(message.getBody());
      if ("ResolvePersonByInstitutionalEmailResult".equals(result.messageType())) resolutions.apply(result);
      else applications.applyResult(result);
      channel.basicAck(tag, false);
    } catch (RuntimeException ex) {
      log.warn("academic import result failed messageId={} error={}",
          message.getMessageProperties().getMessageId(), ex.toString());
      if (retryPublisher.retry(message, "academic.results.retry")) channel.basicAck(tag, false);
      else channel.basicNack(tag, false, false);
    }
  }
}
