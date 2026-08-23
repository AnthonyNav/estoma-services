package com.estoma.platformbff.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.security.ProjectionEventQuarantineService;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.rabbitmq.client.Channel;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
class ProjectionEventListenerTest {
  @Mock MessageEnvelopeCodec codec;
  @Mock ProjectionEventDispatcher dispatcher;
  @Mock ProjectionEventQuarantineService quarantine;
  @Mock Channel channel;

  private ProjectionEventListener listener;

  @BeforeEach
  void setUp() {
    listener = new ProjectionEventListener(codec, dispatcher, quarantine);
  }

  @Test
  void malformedEventIsAckedOnlyAfterDurableQuarantine() throws Exception {
    Message message = message();
    when(codec.readEvent(message.getBody())).thenThrow(new IllegalArgumentException("bad json"));

    listener.onMessage(message, channel);

    verify(quarantine).quarantine(any(), eq("MALFORMED_PROJECTION_EVENT"), eq(message.getBody()));
    verify(channel).basicAck(42L, false);
  }

  @Test
  void quarantineFailureNacksAndRequeues() throws Exception {
    Message message = message();
    when(codec.readEvent(message.getBody())).thenThrow(new IllegalArgumentException("bad json"));
    doThrow(new IllegalStateException("db unavailable"))
        .when(quarantine).quarantine(any(), anyString(), any());

    listener.onMessage(message, channel);

    verify(channel).basicNack(42L, false, true);
  }

  @Test
  void unsupportedEventIsQuarantinedBeforeAck() throws Exception {
    Message message = message();
    EventEnvelope event = org.mockito.Mockito.mock(EventEnvelope.class);
    UUID messageId = UUID.randomUUID();
    when(event.messageId()).thenReturn(messageId);
    when(codec.readEvent(message.getBody())).thenReturn(event);
    when(dispatcher.dispatch(event)).thenReturn(ProjectionEventDispatcher.DispatchOutcome.UNSUPPORTED);

    listener.onMessage(message, channel);

    verify(quarantine).quarantine(messageId, "UNSUPPORTED_PROJECTION_EVENT", message.getBody());
    verify(channel).basicAck(42L, false);
  }

  private static Message message() {
    var properties = new MessageProperties();
    properties.setDeliveryTag(42L);
    properties.setMessageId(UUID.randomUUID().toString());
    return new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
  }
}
