package com.estoma.communications.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.communications.application.ProjectionDispatcher;
import com.estoma.communications.application.ProjectionOutcome;
import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

class EventListenerTest {

  private static final long TAG = 12L;
  private final EnvelopeJsonCodec codec = mock(EnvelopeJsonCodec.class);
  private final EnvelopeValidator validator = mock(EnvelopeValidator.class);
  private final ProjectionDispatcher dispatcher = mock(ProjectionDispatcher.class);
  private final TransientFailureClassifier classifier = mock(TransientFailureClassifier.class);
  private final FailureRouter failureRouter = mock(FailureRouter.class);
  private final Channel channel = mock(Channel.class);
  private final EventListener listener = new EventListener(codec, validator, dispatcher, classifier, failureRouter);

  @Test
  void appliedEventAcksAfterProjectionCommit() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenReturn(new ProjectionOutcome.Applied());

    listener.onMessage(message, channel);

    verify(channel).basicAck(TAG, false);
  }

  @Test
  void failedRetryPublicationNacksOriginal() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    RuntimeException failure = new RuntimeException("temporary");
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(true);
    org.mockito.Mockito.doThrow(new IllegalStateException("confirm timeout"))
        .when(failureRouter).route(eq(message), eq(FailureRouter.EVENTS), eq(failure), eq(true));

    listener.onMessage(message, channel);

    verify(channel, never()).basicAck(TAG, false);
    verify(channel).basicNack(TAG, false, true);
  }

  private static Message message() {
    MessageProperties properties = new MessageProperties();
    properties.setDeliveryTag(TAG);
    return new Message("{}".getBytes(), properties);
  }

  private static MessageEnvelope envelope() {
    UUID personId = UUID.randomUUID();
    return new MessageEnvelope(
        UUID.randomUUID(), "PersonCreated", 1, MessageKind.EVENT, "users", Instant.now(), UUID.randomUUID(), null,
        null, null, personId, 1, Actor.system("users"),
        new ObjectMapper().createObjectNode().put("personId", personId.toString()).put("status", "ACTIVE"));
  }
}
