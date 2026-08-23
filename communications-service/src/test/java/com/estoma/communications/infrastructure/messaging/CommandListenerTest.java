package com.estoma.communications.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.communications.application.CommandDispatcher;
import com.estoma.communications.application.CommandOutcome;
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

class CommandListenerTest {

  private static final long TAG = 11L;
  private final EnvelopeJsonCodec codec = mock(EnvelopeJsonCodec.class);
  private final EnvelopeValidator validator = mock(EnvelopeValidator.class);
  private final CommandDispatcher dispatcher = mock(CommandDispatcher.class);
  private final TransientFailureClassifier classifier = mock(TransientFailureClassifier.class);
  private final FailureRouter failureRouter = mock(FailureRouter.class);
  private final Channel channel = mock(Channel.class);
  private final CommandListener listener = new CommandListener(codec, validator, dispatcher, classifier, failureRouter);

  @Test
  void successfulCommandAcksAfterDispatcherReturns() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenReturn(new CommandOutcome.Succeeded(1));

    listener.onMessage(message, channel);

    verify(channel).basicAck(TAG, false);
    verify(channel, never()).basicNack(TAG, false, true);
  }

  @Test
  void transientFailureAcksOnlyAfterRetryPublicationReturns() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    RuntimeException failure = new RuntimeException("temporary");
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(true);

    listener.onMessage(message, channel);

    verify(failureRouter).route(eq(message), eq(FailureRouter.COMMANDS), eq(failure), eq(true));
    verify(channel).basicAck(TAG, false);
  }

  @Test
  void failedRetryPublicationNacksOriginalForBrokerRedelivery() throws Exception {
    Message message = message();
    MessageEnvelope envelope = envelope();
    RuntimeException failure = new RuntimeException("temporary");
    when(codec.decode(message.getBody())).thenReturn(envelope);
    when(dispatcher.dispatch(envelope)).thenThrow(failure);
    when(classifier.isTransient(failure)).thenReturn(true);
    org.mockito.Mockito.doThrow(new IllegalStateException("confirm timeout"))
        .when(failureRouter).route(eq(message), eq(FailureRouter.COMMANDS), eq(failure), eq(true));

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
    return new MessageEnvelope(
        UUID.randomUUID(), "RegisterInboxVisit", 1, MessageKind.COMMAND, "bff", Instant.now(), UUID.randomUUID(), null,
        UUID.randomUUID(), UUID.randomUUID(), null, null, Actor.system("bff"), new ObjectMapper().createObjectNode());
  }
}
