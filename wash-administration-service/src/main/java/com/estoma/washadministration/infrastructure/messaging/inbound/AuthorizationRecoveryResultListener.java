package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessagingNames;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AuthorizationRecoveryResultListener {

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final AuthorizationRecoveryResultHandler handler;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;

  public AuthorizationRecoveryResultListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      AuthorizationRecoveryResultHandler handler,
      TransientFailureClassifier classifier,
      FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.handler = handler;
    this.classifier = classifier;
    this.failures = failures;
  }

  @RabbitListener(
      id = "authorizationRecoveryResultConsumer",
      queues = MessagingNames.RESULTS_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateAuthorizationResult(envelope);
      handler.handle(envelope);
      channel.basicAck(deliveryTag, false);
    } catch (InvalidMessageException exception) {
      routeInvalid(message, channel, deliveryTag);
    } catch (Exception exception) {
      try {
        failures.routeAfterFailure(message, FailureRouter.RESULTS_TOPOLOGY, exception, classifier.isTransient(exception));
        channel.basicAck(deliveryTag, false);
      } catch (com.estoma.washadministration.infrastructure.messaging.outbound.RabbitPublishException publishException) {
        channel.basicNack(deliveryTag, false, true);
      }
    }
  }

  private void routeInvalid(Message message, Channel channel, long deliveryTag) throws IOException {
    try {
      failures.toDlq(message, FailureRouter.RESULTS_TOPOLOGY);
      channel.basicAck(deliveryTag, false);
    } catch (com.estoma.washadministration.infrastructure.messaging.outbound.RabbitPublishException exception) {
      channel.basicNack(deliveryTag, false, true);
    }
  }
}
