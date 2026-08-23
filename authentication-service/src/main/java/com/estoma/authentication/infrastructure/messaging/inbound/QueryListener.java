package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.AccountStatusSnapshotQueryService;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.messaging.outbound.RabbitPublishException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(
    prefix = "estoma.messaging", name = "listeners-enabled", havingValue = "true", matchIfMissing = true)
public class QueryListener {

  private final EnvelopeJsonCodec codec;
  private final QueryEnvelopeValidator validator;
  private final AccountStatusSnapshotQueryService queries;
  private final TransientFailureClassifier classifier;
  private final FailureRouter failures;

  public QueryListener(
      EnvelopeJsonCodec codec,
      QueryEnvelopeValidator validator,
      AccountStatusSnapshotQueryService queries,
      TransientFailureClassifier classifier,
      FailureRouter failures) {
    this.codec = codec;
    this.validator = validator;
    this.queries = queries;
    this.classifier = classifier;
    this.failures = failures;
  }

  @RabbitListener(
      id = "queryConsumer",
      queues = MessagingNames.AUTHENTICATION_QUERY_QUEUE,
      containerFactory = "manualAckContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    long tag = message.getMessageProperties().getDeliveryTag();
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
      validator.validate(envelope);
    } catch (RuntimeException invalid) {
      route(message, channel, tag, invalid, false);
      return;
    }

    try {
      queries.respond(envelope);
      channel.basicAck(tag, false);
    } catch (RuntimeException failure) {
      route(message, channel, tag, failure, classifier.isTransient(failure));
    }
  }

  private void route(
      Message message, Channel channel, long tag, Throwable failure, boolean transientFailure)
      throws IOException {
    try {
      failures.routeAfterFailure(message, FailureRouter.QUERY_TOPOLOGY, failure, transientFailure);
      channel.basicAck(tag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(tag, false, true);
    }
  }
}
