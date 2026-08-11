package com.estoma.academic.messaging;

import com.estoma.academic.application.AcademicCommandDispatcher;
import com.estoma.academic.contract.MessageEnvelope;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class CommandListener {
  private final EnvelopeCodec codec; private final AcademicCommandDispatcher dispatcher; private final RetryPublisher retryPublisher;
  CommandListener(EnvelopeCodec codec, AcademicCommandDispatcher dispatcher, RetryPublisher retryPublisher) { this.codec = codec; this.dispatcher = dispatcher; this.retryPublisher = retryPublisher; }
  @RabbitListener(id = "academicCommandListener", queues = AcademicTopology.COMMAND_QUEUE, containerFactory = "manualAckContainerFactory")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      MessageEnvelope envelope = codec.decode(message.getBody());
      dispatcher.dispatch(envelope); // commits Inbox + outcome Outbox before the delivery is acknowledged.
      channel.basicAck(tag, false);
    } catch (RuntimeException ex) {
      if (retryPublisher.retry(message, "academic.commands.retry")) channel.basicAck(tag, false);
      else channel.basicNack(tag, false, false);
    }
  }
}
