package com.estoma.users.messaging.inbound;

import com.estoma.users.application.PersonCommandDispatcher;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The AMQP entry point for durable commands. Not transactional itself —
 * it validates, calls the dispatcher (which opens and closes the one
 * transaction), and then decides ACK / retry-tier / DLQ. Nothing it does
 * after the dispatcher returns can roll back the commit
 * (docs/system-contract.md §7).
 */
@Component
public class CommandListener {

  private static final Logger log = LoggerFactory.getLogger(CommandListener.class);

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final PersonCommandDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;

  public CommandListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      PersonCommandDispatcher dispatcher,
      TransientFailureClassifier classifier,
      FailureRouter failureRouter) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failureRouter = failureRouter;
  }

  @RabbitListener(
      id = "commandListener", // referenced by CommandConsumerHealthIndicator
      queues = AmqpNames.QUEUE_COMMANDS,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();

    // (1) decode + structural validation. Any failure here is PERMANENT.
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validate(envelope);
    } catch (Exception e) {
      log.error("invalid message on {}: {}", AmqpNames.QUEUE_COMMANDS, e.toString());
      failureRouter.toDlq(message, e);
      channel.basicAck(deliveryTag, false);
      return;
    }

    // (2) business processing: exactly one local transaction inside dispatch().
    try {
      dispatcher.dispatch(envelope);
      // (3) commit already happened; result + event rows are durable.
      channel.basicAck(deliveryTag, false); // ◀ the ACK. After commit. Nothing else here.
    } catch (Exception e) {
      boolean transientFailure = classifier.isTransient(e);
      log.warn(
          "command {} (messageId={}) failed, transient={}: {}",
          envelope.messageType(),
          envelope.messageId(),
          transientFailure,
          e.toString());
      failureRouter.routeAfterFailure(message, e, transientFailure);
      channel.basicAck(deliveryTag, false);
    }
  }
}
