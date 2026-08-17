package com.estoma.authorization.infrastructure.messaging;

import com.estoma.authorization.contracts.MessageEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

/** Structured message lifecycle logging without serializing payloads or secrets. */
@Component
public final class MessagingLog {

  private static final Logger LOG = LoggerFactory.getLogger(MessagingLog.class);

  public void processed(
      MessageEnvelope envelope,
      Message message,
      String consumer,
      String processingResult,
      String errorCode) {
    var event = LOG.atInfo()
        .addKeyValue("messageId", envelope.messageId())
        .addKeyValue("messageType", envelope.messageType())
        .addKeyValue("messageVersion", envelope.messageVersion())
        .addKeyValue("producer", envelope.producer())
        .addKeyValue("consumer", consumer)
        .addKeyValue("correlationId", envelope.correlationId())
        .addKeyValue("causationId", envelope.causationId())
        .addKeyValue("operationId", envelope.operationId())
        .addKeyValue("aggregateId", envelope.aggregateId())
        .addKeyValue("actorType", envelope.actor() == null ? null : envelope.actor().type())
        .addKeyValue("routingKey", message.getMessageProperties().getReceivedRoutingKey())
        .addKeyValue("processingResult", processingResult)
        .addKeyValue("attempt", attempt(message));
    if (errorCode != null) {
      event.addKeyValue("errorCode", errorCode);
    }
    event.log("authorization message processed");
  }

  public void transportFailure(
      MessageEnvelope envelope,
      Message message,
      String consumer,
      String processingResult,
      Throwable failure) {
    var event = LOG.atWarn()
        .addKeyValue("messageId", envelope == null ? message.getMessageProperties().getMessageId() : envelope.messageId())
        .addKeyValue("messageType", envelope == null ? null : envelope.messageType())
        .addKeyValue("messageVersion", envelope == null ? null : envelope.messageVersion())
        .addKeyValue("producer", envelope == null ? null : envelope.producer())
        .addKeyValue("consumer", consumer)
        .addKeyValue("correlationId", envelope == null ? null : envelope.correlationId())
        .addKeyValue("causationId", envelope == null ? null : envelope.causationId())
        .addKeyValue("operationId", envelope == null ? null : envelope.operationId())
        .addKeyValue("aggregateId", envelope == null ? null : envelope.aggregateId())
        .addKeyValue("actorType", envelope == null || envelope.actor() == null ? null : envelope.actor().type())
        .addKeyValue("routingKey", message.getMessageProperties().getReceivedRoutingKey())
        .addKeyValue("processingResult", processingResult)
        .addKeyValue("attempt", attempt(message));
    if (failure != null) {
      event.addKeyValue("errorCode", failure.getClass().getSimpleName());
    }
    event.log("authorization message transport outcome");
  }

  private static int attempt(Message message) {
    Object value = message.getMessageProperties().getHeaders().get(AmqpNames.HEADER_ATTEMPT);
    return value instanceof Number number && number.intValue() > 0 ? number.intValue() : 1;
  }
}
