package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.security.ProjectionEventQuarantineService;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class ProjectionEventListener {
  private static final Logger log = LoggerFactory.getLogger(ProjectionEventListener.class);

  private final MessageEnvelopeCodec codec;
  private final ProjectionEventDispatcher dispatcher;
  private final ProjectionEventQuarantineService quarantine;

  public ProjectionEventListener(
      MessageEnvelopeCodec codec,
      ProjectionEventDispatcher dispatcher,
      ProjectionEventQuarantineService quarantine) {
    this.codec = codec;
    this.dispatcher = dispatcher;
    this.quarantine = quarantine;
  }

  @RabbitListener(
      id = "projectionEventListener",
      queues = MessagingTopology.PROJECTION_EVENTS_QUEUE,
      containerFactory = "resultsContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    UUID transportMessageId = transportMessageId(message);
    try {
      EventEnvelope event;
      try {
        event = codec.readEvent(message.getBody());
      } catch (RuntimeException malformed) {
        quarantine.quarantine(transportMessageId, "MALFORMED_PROJECTION_EVENT", message.getBody());
        channel.basicAck(deliveryTag, false);
        return;
      }

      ProjectionEventDispatcher.DispatchOutcome outcome = dispatcher.dispatch(event);
      if (outcome != ProjectionEventDispatcher.DispatchOutcome.APPLIED) {
        String reason = outcome == ProjectionEventDispatcher.DispatchOutcome.UNSUPPORTED
            ? "UNSUPPORTED_PROJECTION_EVENT"
            : "UNTRUSTED_PROJECTION_EVENT";
        UUID messageId = event.messageId() == null ? transportMessageId : event.messageId();
        quarantine.quarantine(messageId, reason, message.getBody());
        log.warn("quarantined projection event messageId={} reason={}", messageId, reason);
      }
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException technicalFailure) {
      log.warn(
          "projection event processing failed before safe isolation messageId={} category=technical",
          transportMessageId);
      channel.basicNack(deliveryTag, false, true);
    }
  }

  private static UUID transportMessageId(Message message) {
    String value = message.getMessageProperties().getMessageId();
    try {
      return value == null ? null : UUID.fromString(value);
    } catch (IllegalArgumentException invalid) {
      return null;
    }
  }
}
