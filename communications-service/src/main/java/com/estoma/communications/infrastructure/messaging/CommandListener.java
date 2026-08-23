package com.estoma.communications.infrastructure.messaging;

import com.estoma.communications.application.CommandDispatcher;
import com.estoma.communications.application.CommandOutcome;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessagingNames;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class CommandListener {
  private static final Logger log = LoggerFactory.getLogger(CommandListener.class);
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final CommandDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failureRouter;

  public CommandListener(EnvelopeJsonCodec codec, EnvelopeValidator validator, CommandDispatcher dispatcher,
      TransientFailureClassifier classifier, FailureRouter failureRouter) {
    this.codec=codec; this.validator=validator; this.dispatcher=dispatcher; this.classifier=classifier; this.failureRouter=failureRouter;
  }

  @RabbitListener(id = "commandConsumer", queues = MessagingNames.COMMAND_QUEUE, containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag=message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    try { envelope=codec.decode(message.getBody()); validator.validateCommand(envelope); }
    catch (Exception invalid) { routeThenAck(message,channel,tag,FailureRouter.COMMANDS,invalid,false); return; }
    try {
      CommandOutcome outcome=dispatcher.dispatch(envelope);
      log.info("command {} processed as {}", envelope.messageType(), outcome.getClass().getSimpleName());
      channel.basicAck(tag,false);
    } catch (Exception failure) {
      routeThenAck(message,channel,tag,FailureRouter.COMMANDS,failure,classifier.isTransient(failure));
    }
  }

  private void routeThenAck(Message message, Channel channel, long tag, FailureRouter.Topology topology,
      Throwable failure, boolean transientFailure) throws IOException {
    try { failureRouter.route(message,topology,failure,transientFailure); channel.basicAck(tag,false); }
    catch (RuntimeException publicationFailure) { channel.basicNack(tag,false,true); }
  }
}
