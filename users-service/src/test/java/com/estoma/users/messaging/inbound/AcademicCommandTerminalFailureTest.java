package com.estoma.users.messaging.inbound;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.application.PersonCommandDispatcher;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.observability.ConsumerMetrics;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class AcademicCommandTerminalFailureTest {

  private static final long DELIVERY_TAG = 19L;

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
  void exhaustedAcademicFailurePersistsFailedResultInsteadOfDlqOnly() throws Exception {
    Message message = message();
    MessageEnvelope envelope = academicEnvelope();
    RuntimeException failure = new RuntimeException("database unavailable");
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(true);
    when(failureRouter.commandRetryAvailable(message, true)).thenReturn(false);

    listener.onMessage(message, channel);

    verify(dispatcher).fail(envelope, "TECHNICAL_FAILURE");
    verify(failureRouter, never()).routeAfterFailure(any(), any(), anyBoolean());
    verify(failureRouter, never()).toDlq(any(), any());
    verify(channel).basicAck(DELIVERY_TAG, false);
  }

  @Test
  void failedTerminalResultPersistenceFallsBackToConfirmedDlq() throws Exception {
    Message message = message();
    MessageEnvelope envelope = academicEnvelope();
    RuntimeException failure = new RuntimeException("database unavailable");
    RuntimeException persistenceFailure = new RuntimeException("outbox unavailable");
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(false);
    when(failureRouter.commandRetryAvailable(message, false)).thenReturn(false);
    doThrow(persistenceFailure).when(dispatcher).fail(envelope, "TECHNICAL_FAILURE");

    listener.onMessage(message, channel);

    verify(failureRouter).toDlq(message, persistenceFailure);
    verify(channel).basicAck(DELIVERY_TAG, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
  }

  private static Message message() {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(DELIVERY_TAG);
    return new Message("{}".getBytes(), properties);
  }

  private static MessageEnvelope academicEnvelope() {
    return new MessageEnvelope(
        UUID.randomUUID(), "UpdatePerson", 1, MessageKind.COMMAND, "academic", Instant.now(),
        UUID.randomUUID(), null, UUID.randomUUID(), "student-import:test", UUID.randomUUID(), null,
        new Actor.System("academic"), null);
  }
}
