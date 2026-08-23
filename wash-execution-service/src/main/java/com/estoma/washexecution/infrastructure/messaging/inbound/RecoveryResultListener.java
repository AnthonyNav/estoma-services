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
public final class RecoveryResultListener {
  private final EnvelopeJsonCodec codec; private final EnvelopeValidator validator; private final RecoveryResultInboxProcessor processor;
  private final TransientFailureClassifier classifier; private final FailureRouter failures;
  public RecoveryResultListener(EnvelopeJsonCodec codec,EnvelopeValidator validator,RecoveryResultInboxProcessor processor,TransientFailureClassifier classifier,FailureRouter failures){this.codec=codec;this.validator=validator;this.processor=processor;this.classifier=classifier;this.failures=failures;}
  @RabbitListener(id="washExecutionRecoveryResultConsumer",queues=MessagingNames.RESULT_QUEUE,containerFactory="manualAckContainerFactory")
  public void onMessage(Message message,Channel channel)throws IOException{
    long tag=message.getMessageProperties().getDeliveryTag(); MessageEnvelope e;
    try{e=codec.decode(message.getBody());validator.validateResult(e);}catch(Exception invalid){routeInvalid(message,channel,tag);return;}
    try{processor.process(e);channel.basicAck(tag,false);}catch(Exception error){routeTechnical(message,channel,tag,error);}
  }
  private void routeInvalid(Message m,Channel c,long tag)throws IOException{try{failures.invalid(m,FailureRouter.RESULT);c.basicAck(tag,false);}catch(RabbitPublishException ex){c.basicNack(tag,false,true);}}
  private void routeTechnical(Message m,Channel c,long tag,Exception e)throws IOException{try{failures.afterTechnicalFailure(m,FailureRouter.RESULT,classifier.isTransient(e));c.basicAck(tag,false);}catch(RabbitPublishException ex){c.basicNack(tag,false,true);}}
}
