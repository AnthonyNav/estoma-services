package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessagingNames;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washexecution.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public final class EventMessageListener {
  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final EventInboxProcessor processor;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;

  public EventMessageListener(EnvelopeJsonCodec codec, EnvelopeValidator validator, EventInboxProcessor processor,
      TransientFailureClassifier classifier, FailureRouter failures) {
    this.codec=codec; this.validator=validator; this.processor=processor; this.classifier=classifier; this.failures=failures;
  }

  @RabbitListener(id="washExecutionEventConsumer",queues=MessagingNames.EVENT_QUEUE,containerFactory="manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    try { envelope=codec.decode(message.getBody()); validator.validateEvent(envelope); }
    catch (Exception invalid) { routeInvalid(message,channel,tag); return; }
    try { processor.process(envelope); channel.basicAck(tag,false); }
    catch (Exception error) { routeTechnical(message,channel,tag,error); }
  }

  private void routeInvalid(Message message, Channel channel, long tag) throws IOException {
    try { failures.invalid(message,FailureRouter.EVENT); channel.basicAck(tag,false); }
    catch (RabbitPublishException ex) { channel.basicNack(tag,false,true); }
  }

  private void routeTechnical(Message message, Channel channel, long tag, Exception error) throws IOException {
    try { failures.afterTechnicalFailure(message,FailureRouter.EVENT,classifier.isTransient(error)); channel.basicAck(tag,false); }
    catch (RabbitPublishException ex) { channel.basicNack(tag,false,true); }
  }
}
