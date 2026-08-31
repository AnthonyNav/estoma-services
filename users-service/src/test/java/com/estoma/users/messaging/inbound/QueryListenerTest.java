package com.estoma.users.messaging.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.application.PersonQueryDispatcher;
import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.outbound.OutboxFailureCode;
import com.estoma.users.messaging.outbound.RabbitPublishException;
import com.estoma.users.observability.QueryConsumerMetrics;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * {@link CommandListenerTest}'s twin for {@link QueryListener}: transient
 * failures are retried through the retry topology, while an exhausted or
 * permanent Query failure first persists a terminal Result. The delivery is
 * ACKed only after that durable outcome (or a confirmed DLQ fallback).
 */
class QueryListenerTest {

  private static final long DELIVERY_TAG = 7L;

  private final EnvelopeJsonCodec codec = mock(EnvelopeJsonCodec.class);
  private final QueryEnvelopeValidator validator = mock(QueryEnvelopeValidator.class);
  private final PersonQueryDispatcher dispatcher = mock(PersonQueryDispatcher.class);
  private final TransientFailureClassifier classifier = mock(TransientFailureClassifier.class);
  private final FailureRouter failureRouter = mock(FailureRouter.class);
  private final QueryConsumerMetrics metrics = mock(QueryConsumerMetrics.class);
  private final MessagingProperties properties = mock(MessagingProperties.class);
  private final Channel channel = mock(Channel.class);

  private final QueryListener listener =
      new QueryListener(codec, validator, dispatcher, classifier, failureRouter, metrics, properties);

  @Test
  void invalidMessageAcksOnlyAfterDlqIsConfirmed() throws Exception {
    Message message = message();
    when(codec.decode(message.getBody())).thenThrow(new RuntimeException("malformed json"));

    listener.onMessage(message, channel);

    verify(metrics).invalidMessage();
    verify(failureRouter).toDlq(eq(message), any(Exception.class), any());
    verify(metrics).deadLettered();
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  void invalidMessageIsNeverAckedWhenTheDlqCannotBeConfirmed() throws Exception {
    Message message = message();
    when(codec.decode(message.getBody())).thenThrow(new RuntimeException("malformed json"));
    doThrow(new RabbitPublishException(OutboxFailureCode.PUBLISH_NACK, UUID.randomUUID(), null))
        .when(failureRouter)
        .toDlq(eq(message), any(Exception.class), any());

    listener.onMessage(message, channel);

    verify(metrics).invalidMessage();
    verify(metrics, never()).deadLettered();
    verify(metrics).technicalFailure();
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(channel).basicNack(DELIVERY_TAG, false, true);
  }

  @Test
  void successfulResolutionAcksAfterTheOutboxRowIsAppended() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);

    listener.onMessage(message, channel);

    verify(dispatcher).resolve(envelope);
    verify(metrics).resolved();
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  void transientFailureAcksOnlyAfterRetryTierIsConfirmed() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    RuntimeException failure = new RuntimeException("transient db hiccup");
    doThrow(failure).when(dispatcher).resolve(envelope);
    when(classifier.isTransient(failure)).thenReturn(true);
    when(properties.queryMaxAttempts()).thenReturn(3);
    when(failureRouter.routeAfterFailure(eq(message), eq(failure), eq(true), any(), eq(3)))
        .thenReturn(FailureRoute.RETRY);

    listener.onMessage(message, channel);

    verify(metrics).technicalFailure();
    verify(metrics).retried();
    verify(metrics, never()).deadLettered();
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  void permanentFailureAcksAfterTerminalResultIsPersisted() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    RuntimeException failure = new RuntimeException("malformed institutionalEmail");
    doThrow(failure).when(dispatcher).resolve(envelope);
    when(classifier.isTransient(failure)).thenReturn(false);
    listener.onMessage(message, channel);

    verify(dispatcher).fail(envelope, "TECHNICAL_FAILURE");
    verify(metrics).technicalFailure();
    verify(metrics, never()).deadLettered();
    verify(metrics, never()).retried();
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  void permanentFailureIsRequeuedWhenTerminalResultAndDlqFallbackCannotBePersisted() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    RuntimeException failure = new RuntimeException("permanent failure");
    doThrow(failure).when(dispatcher).resolve(envelope);
    when(classifier.isTransient(failure)).thenReturn(false);
    doThrow(new RuntimeException("outbox unavailable")).when(dispatcher)
        .fail(envelope, "TECHNICAL_FAILURE");
    doThrow(new RabbitPublishException(OutboxFailureCode.PUBLISH_RETURNED, UUID.randomUUID(), null)).when(failureRouter)
        .toDlq(eq(message), any(Exception.class), any());

    listener.onMessage(message, channel);

    verify(dispatcher).fail(envelope, "TECHNICAL_FAILURE");
    verify(metrics, never()).deadLettered();
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(channel).basicNack(DELIVERY_TAG, false, true);
  }

  private static Message message() {
    MessageProperties props = new MessageProperties();
    props.setDeliveryTag(DELIVERY_TAG);
    return new Message("{}".getBytes(), props);
  }

  private static MessageEnvelope envelope() {
    return new MessageEnvelope(
        UUID.randomUUID(),
        "ResolvePersonByInstitutionalEmail",
        1,
        MessageKind.QUERY,
        "academic",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        null,
        null,
        null,
        new Actor.System("academic"),
        null);
  }
}
