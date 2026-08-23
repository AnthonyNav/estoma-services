package com.estoma.platformbff.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.operations.ResultDispatcher;
import com.estoma.platformbff.application.operations.ResultQuarantineService;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

@ExtendWith(MockitoExtension.class)
class ResultListenerTest {
  @Mock MessageEnvelopeCodec codec;
  @Mock ResultDispatcher dispatcher;
  @Mock ResultQuarantineService quarantine;
  @Mock Channel channel;

  private ResultListener listener;

  @BeforeEach
  void setUp() {
    listener = new ResultListener(codec, dispatcher, quarantine);
  }

  @Test
  void malformedResultIsAckedOnlyAfterQuarantineSucceeds() throws Exception {
    Message message = message();
    when(codec.readIncomingResult(message.getBody())).thenThrow(new IllegalArgumentException("bad json"));

    listener.onMessage(message, channel);

    verify(quarantine).quarantine(any(), eq("MALFORMED_RESULT"), eq(message.getBody()));
    verify(channel).basicAck(42L, false);
  }

  @Test
  void quarantineFailureNacksAndRequeuesOriginalDelivery() throws Exception {
    Message message = message();
    when(codec.readIncomingResult(message.getBody())).thenThrow(new IllegalArgumentException("bad json"));
    doThrow(new IllegalStateException("db unavailable"))
        .when(quarantine).quarantine(any(), anyString(), any());

    listener.onMessage(message, channel);

    verify(channel).basicNack(42L, false, true);
  }

  @Test
  void technicalDispatchFailureNacksAndRequeues() throws Exception {
    Message message = message();
    IncomingResult result = result();
    when(codec.readIncomingResult(message.getBody())).thenReturn(result);
    when(dispatcher.dispatch(result)).thenThrow(new IllegalStateException("db unavailable"));

    listener.onMessage(message, channel);

    verify(channel).basicNack(42L, false, true);
  }

  @Test
  void untrustedResultIsQuarantinedBeforeAck() throws Exception {
    Message message = message();
    IncomingResult result = result();
    when(codec.readIncomingResult(message.getBody())).thenReturn(result);
    when(dispatcher.dispatch(result)).thenReturn(ResultDispatcher.DispatchOutcome.UNTRUSTED);

    listener.onMessage(message, channel);

    verify(quarantine).quarantine(eq(result.messageId()), eq("UNTRUSTED_RESULT"), eq(message.getBody()));
    verify(channel).basicAck(42L, false);
  }

  private static Message message() {
    var properties = new MessageProperties();
    properties.setDeliveryTag(42L);
    properties.setMessageId(UUID.randomUUID().toString());
    return new Message("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8), properties);
  }

  private static IncomingResult result() {
    return new IncomingResult(
        UUID.randomUUID(), "CreatePersonSucceeded", 1, MessageKind.RESULT, "users",
        Instant.now(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
        null, null, "SUCCEEDED", "{}", null, null);
  }
}
