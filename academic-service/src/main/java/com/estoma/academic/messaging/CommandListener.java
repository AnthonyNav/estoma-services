package com.estoma.academic.messaging;

import com.estoma.academic.application.AcademicCommandDispatcher;
import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.observability.AcademicMessagingMetrics;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class CommandListener {
  private static final Logger log = LoggerFactory.getLogger(CommandListener.class);
  private final EnvelopeCodec codec; private final AcademicCommandDispatcher dispatcher; private final RetryPublisher retryPublisher;
  private final AcademicMessagingMetrics metrics;
  CommandListener(EnvelopeCodec codec, AcademicCommandDispatcher dispatcher, RetryPublisher retryPublisher, AcademicMessagingMetrics metrics) { this.codec = codec; this.dispatcher = dispatcher; this.retryPublisher = retryPublisher; this.metrics = metrics; }
  @RabbitListener(id = "academicCommandListener", queues = AcademicTopology.COMMAND_QUEUE, containerFactory = "manualAckContainerFactory")
  public void consume(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    try {
      MessageEnvelope envelope = codec.decode(message.getBody());
       AcademicCommandDispatcher.Outcome outcome = dispatcher.dispatch(envelope); // commits Inbox + outcome Outbox before the delivery is acknowledged.
       metrics.consumed();
       if ("DUPLICATE".equals(outcome.status())) metrics.duplicate();
       channel.basicAck(tag, false);
     } catch (RuntimeException ex) {
       log.warn("academic command consumption failed messageId={} error={}", message.getMessageProperties().getMessageId(), ex.toString());
       RetryPublisher.RetryOutcome outcome = retryPublisher.retry(message, "academic.commands.retry");
       if (outcome == RetryPublisher.RetryOutcome.PUBLISHED) {
         metrics.retry(); channel.basicAck(tag, false);
       } else if (outcome == RetryPublisher.RetryOutcome.EXHAUSTED) {
         metrics.dlq(); channel.basicNack(tag, false, false);
       } else {
         channel.basicNack(tag, false, true);
       }
    }
  }
}
