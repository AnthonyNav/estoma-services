package com.estoma.washexecution.infrastructure.messaging.inbound;

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
public final class CommandMessageListener {
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final CommandMessageMapper mapper;
  private final CommandInboxProcessor processor;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;

  public CommandMessageListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      CommandMessageMapper mapper,
      CommandInboxProcessor processor,
      TransientFailureClassifier classifier,
      FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.processor = processor;
    this.classifier = classifier;
    this.failures = failures;
  }

  @RabbitListener(id="washExecutionCommandConsumer",queues=MessagingNames.COMMAND_QUEUE,containerFactory="manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    com.estoma.washexecution.application.command.CommandRequest request;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateCommand(envelope);
      request = mapper.map(envelope);
    } catch (Exception invalid) {
      routeInvalid(message, channel, tag);
      return;
    }
    try {
      processor.process(envelope, request);
      channel.basicAck(tag, false);
    } catch (Exception technical) {
      routeTechnical(message, channel, tag, technical);
    }
  }

  private void routeInvalid(Message message, Channel channel, long tag) throws IOException {
    try { failures.invalid(message, FailureRouter.COMMAND); channel.basicAck(tag, false); }
    catch (RabbitPublishException ex) { channel.basicNack(tag, false, true); }
  }

  private void routeTechnical(Message message, Channel channel, long tag, Exception error) throws IOException {
    try {
      failures.afterTechnicalFailure(message, FailureRouter.COMMAND, classifier.isTransient(error));
      channel.basicAck(tag, false);
    } catch (RabbitPublishException ex) {
      channel.basicNack(tag, false, true);
    }
  }
}
