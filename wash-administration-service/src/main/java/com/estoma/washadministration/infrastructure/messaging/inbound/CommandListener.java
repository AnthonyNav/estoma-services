package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.application.command.CommandOutcome;
import com.estoma.washadministration.application.command.WashAdministrationCommandDispatcher;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessagingNames;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washadministration.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CommandListener {

  private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final CommandMessageMapper mapper;
  private final WashAdministrationCommandDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;

  public CommandListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      CommandMessageMapper mapper,
      WashAdministrationCommandDispatcher dispatcher,
      TransientFailureClassifier classifier,
      FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failures = failures;
  }

  @RabbitListener(
      id = "commandConsumer",
      queues = MessagingNames.COMMAND_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    com.estoma.washadministration.application.command.CommandRequest request;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateCommand(envelope);
      request = mapper.map(envelope);
    } catch (Exception exception) {
      routeInvalid(message, channel, deliveryTag, exception);
      return;
    }
    try {
      dispatcher.dispatch(request);
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException exception) {
      nackRequeue(channel, deliveryTag);
    } catch (Exception exception) {
      routeTechnical(message, channel, deliveryTag, exception);
    }
  }

  private void routeInvalid(Message message, Channel channel, long deliveryTag, Exception exception)
      throws IOException {
    try {
      failures.toDlq(message, FailureRouter.COMMAND_TOPOLOGY);
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException publishException) {
      nackRequeue(channel, deliveryTag);
    }
    log.warn("invalid command message routed to DLQ: {}", exception.getMessage());
  }

  private void routeTechnical(Message message, Channel channel, long deliveryTag, Exception exception)
      throws IOException {
    try {
      failures.routeAfterFailure(message, FailureRouter.COMMAND_TOPOLOGY, exception, classifier.isTransient(exception));
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException publishException) {
      nackRequeue(channel, deliveryTag);
    }
  }

  private static void nackRequeue(Channel channel, long deliveryTag) throws IOException {
    channel.basicNack(deliveryTag, false, true);
  }
}
