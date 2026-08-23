package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.AccountStatusSnapshotQueryService;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.infrastructure.messaging.AmqpNames;
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

  private static final int MAX_ATTEMPTS = 3;

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
      dlqOrRequeue(message, channel, tag, invalid);
      return;
    }

    try {
      queries.respond(envelope);
      channel.basicAck(tag, false);
    } catch (RuntimeException failure) {
      boolean transientFailure = classifier.isTransient(failure);
      if (!transientFailure || isFinalAttempt(message)) {
        terminalizeOrDlq(envelope, message, channel, tag, failure);
      } else {
        retryOrRequeue(message, channel, tag, failure);
      }
    }
  }

  private void terminalizeOrDlq(
      MessageEnvelope envelope, Message message, Channel channel, long tag, RuntimeException originalFailure)
      throws IOException {
    try {
      queries.fail(envelope, "TECHNICAL_FAILURE");
      channel.basicAck(tag, false);
    } catch (RuntimeException terminalPersistenceFailure) {
      dlqOrRequeue(message, channel, tag, terminalPersistenceFailure);
    }
  }

  private void retryOrRequeue(
      Message message, Channel channel, long tag, RuntimeException failure) throws IOException {
    try {
      failures.routeAfterFailure(message, FailureRouter.QUERY_TOPOLOGY, failure, true);
      channel.basicAck(tag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(tag, false, true);
    }
  }

  private void dlqOrRequeue(Message message, Channel channel, long tag, Throwable failure)
      throws IOException {
    try {
      failures.toDlq(message, FailureRouter.QUERY_TOPOLOGY, failure);
      channel.basicAck(tag, false);
    } catch (RabbitPublishException publishFailure) {
      channel.basicNack(tag, false, true);
    }
  }

  private static boolean isFinalAttempt(Message message) {
    Object value = message.getMessageProperties().getHeaders().get(AmqpNames.HEADER_ATTEMPT);
    int attempt = value instanceof Number number ? number.intValue() : 0;
    return attempt >= MAX_ATTEMPTS;
  }
}
