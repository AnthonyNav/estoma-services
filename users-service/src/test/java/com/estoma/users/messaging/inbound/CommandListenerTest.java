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

import com.estoma.users.application.CommandOutcome;
import com.estoma.users.application.PersonCommandDispatcher;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.messaging.outbound.OutboxFailureCode;
import com.estoma.users.messaging.outbound.RabbitPublishException;
import com.estoma.users.observability.ConsumerMetrics;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/**
 * Verifies that {@link CommandListener} only ACKs the original delivery
 * once a retry/DLQ republication is confirmed, and NACKs with requeue
 * instead when it cannot be (docs/system-contract.md §7, Round 1) —
 * never a bare {@code send()}-then-ACK.
 */
class CommandListenerTest {

  private static final long DELIVERY_TAG = 7L;

  private final EnvelopeJsonCodec codec = mock(EnvelopeJsonCodec.class);
  private final EnvelopeValidator validator = mock(EnvelopeValidator.class);
  private final PersonCommandDispatcher dispatcher = mock(PersonCommandDispatcher.class);
  private final TransientFailureClassifier classifier = mock(TransientFailureClassifier.class);
  private final FailureRouter failureRouter = mock(FailureRouter.class);
  private final ConsumerMetrics metrics = mock(ConsumerMetrics.class);
  private final Channel channel = mock(Channel.class);

  private final CommandListener listener =
      new CommandListener(codec, validator, dispatcher, classifier, failureRouter, metrics);

  @Test
  void invalidMessageAcksOnlyAfterDlqIsConfirmed() throws Exception {
    Message message = message();
    when(codec.decode(message.getBody())).thenThrow(new RuntimeException("malformed json"));

    listener.onMessage(message, channel);

    verify(metrics).invalidMessage();
    verify(failureRouter).toDlq(eq(message), any(Exception.class));
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
        .toDlq(eq(message), any(Exception.class));

    listener.onMessage(message, channel);

    verify(metrics).invalidMessage();
    verify(metrics, never()).deadLettered();
    verify(metrics).technicalFailure();
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(channel).basicNack(DELIVERY_TAG, false, true);
  }

  @Test
  void successfulProcessingAcksAfterCommit() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenReturn(new CommandOutcome.Succeeded(1));

    listener.onMessage(message, channel);

    verify(metrics).succeeded();
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  void rejectedOutcomeAcksAfterCommit() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenReturn(new CommandOutcome.Rejected(RejectionCode.PERSON_NOT_FOUND));

    listener.onMessage(message, channel);

    verify(metrics).rejected();
    verify(channel).basicAck(DELIVERY_TAG, false);
  }

  @Test
  void transientFailureAcksOnlyAfterRetryTierIsConfirmed() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    RuntimeException failure = new RuntimeException("transient db hiccup");
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(true);
    when(failureRouter.routeAfterFailure(message, failure, true)).thenReturn(FailureRoute.RETRY);

    listener.onMessage(message, channel);

    verify(metrics).technicalFailure();
    verify(metrics).retried();
    verify(metrics, never()).deadLettered();
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  @Test
  void transientFailureIsNeverAckedWhenTheRetryRepublicationCannotBeConfirmed() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    RuntimeException failure = new RuntimeException("transient db hiccup");
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(true);
    when(failureRouter.routeAfterFailure(message, failure, true))
        .thenThrow(new RabbitPublishException(OutboxFailureCode.PUBLISH_TIMEOUT, UUID.randomUUID(), null));

    listener.onMessage(message, channel);

    verify(metrics, times(1)).technicalFailure(); // only the publish-failure path counts it here
    verify(metrics, never()).retried();
    verify(metrics, never()).deadLettered();
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    verify(channel).basicNack(DELIVERY_TAG, false, true);
  }

  @Test
  void permanentFailureAcksOnlyAfterDlqIsConfirmed() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    RuntimeException failure = new RuntimeException("permanent failure");
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(false);
    when(failureRouter.routeAfterFailure(message, failure, false)).thenReturn(FailureRoute.DLQ);

    listener.onMessage(message, channel);

    verify(metrics).deadLettered();
    verify(metrics, never()).retried();
    verify(channel).basicAck(DELIVERY_TAG, false);
  }

  @Test
  void permanentFailureIsNeverAckedWhenTheDlqRepublicationCannotBeConfirmed() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    RuntimeException failure = new RuntimeException("permanent failure");
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(false);
    when(failureRouter.routeAfterFailure(message, failure, false))
        .thenThrow(new RabbitPublishException(OutboxFailureCode.PUBLISH_RETURNED, UUID.randomUUID(), null));

    listener.onMessage(message, channel);

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
        "CreatePerson",
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new Actor.Account(UUID.randomUUID()),
        null);
  }
}
