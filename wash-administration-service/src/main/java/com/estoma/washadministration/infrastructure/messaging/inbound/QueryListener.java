package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.contracts.MessageEnvelope;
import com.estoma.washadministration.contracts.MessagingNames;
import com.estoma.washadministration.contracts.query.GetWashAdministrationConfigurationRequest;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.washadministration.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class QueryListener {

  private static final Logger log = LoggerFactory.getLogger(QueryListener.class);

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final ConfigurationQueryDispatcher dispatcher;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;

  public QueryListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      ConfigurationQueryDispatcher dispatcher,
      TransientFailureClassifier classifier,
      FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.dispatcher = dispatcher;
    this.classifier = classifier;
    this.failures = failures;
  }

  @RabbitListener(
      id = "queryConsumer",
      queues = MessagingNames.QUERY_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long deliveryTag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    GetWashAdministrationConfigurationRequest request;
    try {
      envelope = codec.decode(message.getBody());
      validator.validateConfigurationQuery(envelope);
      if (!"GetWashAdministrationConfiguration".equals(envelope.messageType())) {
        throw new InvalidMessageException("QUERY_TYPE_NOT_ALLOWED");
      }
      request = codec.convertPayload(envelope, GetWashAdministrationConfigurationRequest.class);
    } catch (Exception exception) {
      log.warn("invalid query message routed to DLQ: {}", exception.getMessage());
      routeInvalid(message, channel, deliveryTag);
      return;
    }
    try {
      dispatcher.dispatch(envelope, request.referenceDate());
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException exception) {
      log.warn("query result publication failed; message will be requeued: {}", exception.getMessage());
      channel.basicNack(deliveryTag, false, true);
    } catch (Exception exception) {
      log.warn("query processing failure routed according to retry policy: {}", exception.getMessage());
      try {
        failures.routeAfterFailure(message, FailureRouter.QUERY_TOPOLOGY, exception, classifier.isTransient(exception));
        channel.basicAck(deliveryTag, false);
      } catch (RabbitPublishException publishException) {
        channel.basicNack(deliveryTag, false, true);
      }
    }
  }

  private void routeInvalid(Message message, Channel channel, long deliveryTag) throws IOException {
    try {
      failures.toDlq(message, FailureRouter.QUERY_TOPOLOGY);
      channel.basicAck(deliveryTag, false);
    } catch (RabbitPublishException exception) {
      channel.basicNack(deliveryTag, false, true);
    }
  }
}
