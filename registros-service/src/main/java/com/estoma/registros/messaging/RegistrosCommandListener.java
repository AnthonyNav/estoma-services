package com.estoma.registros.messaging;

import com.estoma.registros.contract.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** V1 simplification: default auto-ack container — see jornadas-service's equivalent listener. */
@Component
public class RegistrosCommandListener {
  private static final Logger log = LoggerFactory.getLogger(RegistrosCommandListener.class);

  private final EnvelopeCodec codec;
  private final RegistroCommandDispatcher dispatcher;

  public RegistrosCommandListener(EnvelopeCodec codec, RegistroCommandDispatcher dispatcher) {
    this.codec = codec;
    this.dispatcher = dispatcher;
  }

  @RabbitListener(queues = AmqpNames.QUEUE_COMMANDS)
  public void consume(Message message) {
    MessageEnvelope envelope = codec.decode(message.getBody());
    log.info("comando recibido messageType={} messageId={}", envelope.messageType(), envelope.messageId());
    dispatcher.dispatch(envelope);
  }
}
