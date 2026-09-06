package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.query.IntegrationQueryMetadata;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public final class IntegrationQueryListener {
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final IntegrationQueryMessageMapper mapper;
  private final IntegrationQueryProcessor processor;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;

  public IntegrationQueryListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      IntegrationQueryMessageMapper mapper,
      IntegrationQueryProcessor processor,
      TransientFailureClassifier classifier,
      FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.processor = processor;
    this.classifier = classifier;
    this.failures = failures;
  }

  @RabbitListener(
      id="washExecutionQueryConsumer",
      queues=MessagingNames.QUERY_QUEUE,
      containerFactory="manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    IntegrationQueryMetadata metadata;

    try {
      envelope = codec.decode(message.getBody());
      validator.validateQuery(envelope);
      metadata = mapper.metadata(envelope);
    } catch (Exception invalid) {
      routeInvalid(message, channel, tag);
      return;
    }

    try {
      if (!requesterAllowed(envelope)) {
        processor.reject(metadata, "QUERY_PRODUCER_NOT_ALLOWED");
      } else {
        try {
          processor.process(metadata, mapper.query(envelope));
        } catch (IllegalArgumentException functional) {
          processor.reject(metadata, stableCode(functional));
        }
      }
      channel.basicAck(tag, false);
    } catch (Exception technical) {
      routeTechnical(message, channel, tag, technical);
    }
  }

  private static boolean requesterAllowed(MessageEnvelope envelope) {
    return "GetReassignmentCandidates".equals(envelope.messageType())
        ? "bff".equals(envelope.producer())
        : "appointments".equals(envelope.producer());
  }

  private static String stableCode(IllegalArgumentException exception) {
    String message = exception.getMessage();
    return message == null || message.isBlank() ? "INVALID_QUERY" : message;
  }

  private void routeInvalid(Message message, Channel channel, long tag) throws IOException {
    try {
      failures.invalid(message, FailureRouter.QUERY);
      channel.basicAck(tag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(tag, false, true);
    }
  }

  private void routeTechnical(Message message, Channel channel, long tag, Exception error) throws IOException {
    try {
      failures.afterTechnicalFailure(message, FailureRouter.QUERY, classifier.isTransient(error));
      channel.basicAck(tag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(tag, false, true);
    }
  }
}
