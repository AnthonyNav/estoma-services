package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.ProjectionDispatcher;
import com.estoma.authentication.application.ProjectionOutcome;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.infrastructure.messaging.AmqpNames;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The AMQP entry point for consumed durable events ({@code PersonCreated}/
 * {@code PersonActivated}/{@code PersonDeactivated}/{@code
 * StudentNumberCorrected}). Same ACK/retry/DLQ shape as {@link
 * CommandListener}, against the single 5s retry tier declared for this
 * queue (see {@code AmqpTopologyConfig}).
 */
@Component
public class EventListener {

  private static final Logger log = LoggerFactory.getLogger(EventListener.class);

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ProjectionDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;

  public EventListener(
      EnvelopeJsonCodec codec, EnvelopeValidator validator, ProjectionDispatcher dispatcher, TransientFailureClassifier classifier, FailureRouter failureRouter) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failureRouter = failureRouter;
  }

  @RabbitListener(id = "eventListener", queues = AmqpNames.QUEUE_EVENTS, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();

    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateEvent(envelope);
    } catch (Exception e) {
      log.error("invalid message on {}: {}", AmqpNames.QUEUE_EVENTS, e.toString());
      try {
        failureRouter.toDlq(message, FailureRouter.EVENTS_TOPOLOGY, e);
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        nackRequeue(channel, deliveryTag);
      }
      return;
    }

    try {
      ProjectionOutcome outcome = dispatcher.dispatch(envelope);
      log.info("event {} processed: {}", envelope.messageType(), describe(outcome));
      channel.basicAck(deliveryTag, false);
    } catch (Exception e) {
      boolean transientFailure = classifier.isTransient(e);
      log.warn("event {} (messageId={}) failed, transient={}: {}", envelope.messageType(), envelope.messageId(), transientFailure, e.toString());
      try {
        failureRouter.routeAfterFailure(message, FailureRouter.EVENTS_TOPOLOGY, e, transientFailure);
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        nackRequeue(channel, deliveryTag);
      }
    }
  }

  private static void nackRequeue(Channel channel, long deliveryTag) throws IOException {
    channel.basicNack(deliveryTag, false, true);
  }

  private static String describe(ProjectionOutcome outcome) {
    return switch (outcome) {
      case ProjectionOutcome.Applied applied -> "applied";
      case ProjectionOutcome.Duplicate duplicate -> "duplicate";
    };
  }
}
