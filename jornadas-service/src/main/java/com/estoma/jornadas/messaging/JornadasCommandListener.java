package com.estoma.jornadas.messaging;

import com.estoma.jornadas.contract.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * V1 simplification: default auto-ack container (no manual Channel/ack, no
 * retry tiers). A decode failure or an unknown messageType is a contract
 * violation and is left to propagate — {@code default-requeue-rejected:
 * false} (application.yml) rejects it without requeue, and the queue's own
 * {@code x-dead-letter-exchange} argument (AmqpTopologyConfig) routes it to
 * the DLQ. A functional rejection never reaches this catch: {@link
 * JornadaCommandDispatcher#dispatch} already turns it into a REJECTED/FAILED
 * Result and returns normally.
 */
@Component
public class JornadasCommandListener {
  private static final Logger log = LoggerFactory.getLogger(JornadasCommandListener.class);

  private final EnvelopeCodec codec;
  private final JornadaCommandDispatcher dispatcher;

  public JornadasCommandListener(EnvelopeCodec codec, JornadaCommandDispatcher dispatcher) {
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
