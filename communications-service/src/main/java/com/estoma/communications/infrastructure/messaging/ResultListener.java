package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.application.projection.AcademicPeriodRecoveryCoordinator;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessagingNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Consumes only the fenced Academic recovery response and routes invalid input separately. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class ResultListener {

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final AcademicPeriodRecoveryCoordinator coordinator;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;

  ResultListener(EnvelopeJsonCodec codec, EnvelopeValidator validator, AcademicPeriodRecoveryCoordinator coordinator,
      TransientFailureClassifier classifier, FailureRouter failureRouter) {
    this.codec = codec;
    this.validator = validator;
    this.coordinator = coordinator;
    this.classifier = classifier;
    this.failureRouter = failureRouter;
  }

  @RabbitListener(id = "resultConsumer", queues = MessagingNames.RESULT_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      MessageEnvelope envelope = codec.decode(message.getBody());
      validator.validateAcademicPeriodRecoveryResult(envelope);
      coordinator.handleResult(envelope);
      channel.basicAck(tag, false);
    } catch (Exception failure) {
      routeThenAck(message, channel, tag, failure, classifier.isTransient(failure));
    }
  }

  private void routeThenAck(Message message, Channel channel, long tag, Throwable failure, boolean transientFailure)
      throws IOException {
    try {
      failureRouter.route(message, FailureRouter.RESULTS, failure, transientFailure);
      channel.basicAck(tag, false);
    } catch (RuntimeException publicationFailure) {
      channel.basicNack(tag, false, true);
    }
  }
}
