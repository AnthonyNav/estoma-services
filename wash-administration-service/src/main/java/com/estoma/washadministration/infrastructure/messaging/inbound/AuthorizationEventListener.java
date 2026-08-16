package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.application.authorization.AuthorizationProjectionUpdater;
import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessagingNames;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washadministration.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationEventListener {

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final AuthorizationEventMapper mapper;
  private final AuthorizationProjectionUpdater updater;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;

  public AuthorizationEventListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      AuthorizationEventMapper mapper,
      AuthorizationProjectionUpdater updater,
      TransientFailureClassifier classifier,
      FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.mapper = mapper;
    this.updater = updater;
    this.classifier = classifier;
    this.failures = failures;
  }

  @RabbitListener(
      id = "authorizationEventConsumer",
      queues = MessagingNames.EVENTS_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateAuthorizationEvent(envelope);
      updater.apply(mapper.map(envelope));
      channel.basicAck(deliveryTag, false);
    } catch (InvalidMessageException exception) {
      routeInvalid(message, channel, deliveryTag);
    } catch (RabbitPublishException exception) {
      channel.basicNack(deliveryTag, false, true);
    } catch (Exception exception) {
      try {
        failures.routeAfterFailure(message, FailureRouter.EVENTS_TOPOLOGY, exception, classifier.isTransient(exception));
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        channel.basicNack(deliveryTag, false, true);
      }
    }
  }

  private void routeInvalid(Message message, Channel channel, long deliveryTag) throws IOException {
    try {
      failures.toDlq(message, FailureRouter.EVENTS_TOPOLOGY);
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException exception) {
      channel.basicNack(deliveryTag, false, true);
    }
  }
}
