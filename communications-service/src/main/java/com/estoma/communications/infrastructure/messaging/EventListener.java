package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.application.ProjectionDispatcher;
import com.estoma.communications.application.ProjectionOutcome;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessagingNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class EventListener {

  private static final Logger log = LoggerFactory.getLogger(EventListener.class);
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ProjectionDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;

  public EventListener(EnvelopeJsonCodec codec, EnvelopeValidator validator, ProjectionDispatcher dispatcher,
      TransientFailureClassifier classifier, FailureRouter failureRouter) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failureRouter = failureRouter;
  }

  @RabbitListener(id = "eventConsumer", queues = MessagingNames.EVENT_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateEvent(envelope);
    } catch (Exception invalid) {
      routeThenAck(message, channel, tag, invalid, false);
      return;
    }

    try {
      ProjectionOutcome outcome = dispatcher.dispatch(envelope);
      log.info("event {} processed as {}", envelope.messageType(), outcome.getClass().getSimpleName());
      channel.basicAck(tag, false);
    } catch (Exception failure) {
      routeThenAck(message, channel, tag, failure, classifier.isTransient(failure));
    }
  }

  private void routeThenAck(Message message, Channel channel, long tag, Throwable failure, boolean transientFailure)
      throws IOException {
    try {
      failureRouter.route(message, FailureRouter.EVENTS, failure, transientFailure);
      channel.basicAck(tag, false);
    } catch (RuntimeException publicationFailure) {
      channel.basicNack(tag, false, true);
    }
  }
}
