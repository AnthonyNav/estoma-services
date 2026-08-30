package com.estoma.authentication.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.rpc.RefreshSessionResponse;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class RpcListenerTest {
  private EnvelopeJsonCodec codec;
  @Mock RpcOperationRouter router;
  private RpcListener listener;

  @BeforeEach
  void setUp() {
    ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
    codec = new EnvelopeJsonCodec(mapper);
    listener = new RpcListener(codec, new EnvelopeValidator(), router);
  }

  @Test
  void refreshReturnsDirectRpcResponseWithExactCorrelationAndCausation() {
    MessageEnvelope request = refreshRequest(new Actor("ANONYMOUS", null, null, "PUBLIC", null), null);
    doReturn(
            new RpcOutcome.Success<>(
                new RefreshSessionResponse("access", "refresh", Instant.parse("2026-09-01T00:00:00Z"))))
        .when(router)
        .route(request);

    MessageEnvelope response = codec.decode(listener.onMessage(message(request)));

    assertThat(response.messageKind()).isEqualTo(MessageKind.RPC_RESPONSE);
    assertThat(response.messageType()).isEqualTo("RefreshSession");
    assertThat(response.messageVersion()).isEqualTo(1);
    assertThat(response.producer()).isEqualTo("authentication");
    assertThat(response.correlationId()).isEqualTo(request.correlationId());
    assertThat(response.causationId()).isEqualTo(request.messageId());
    assertThat(response.operationId()).isNull();
    assertThat(response.aggregateId()).isNull();
    assertThat(response.payload().path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(response.payload().path("data").path("accessToken").asText()).isEqualTo("access");
  }

  @Test
  void refreshWithAccountActorIsRejectedBeforeApplicationRouting() {
    UUID accountId = UUID.randomUUID();
    MessageEnvelope request = refreshRequest(
        new Actor("ACCOUNT", accountId, null, "SELF", null), accountId);

    MessageEnvelope response = codec.decode(listener.onMessage(message(request)));

    assertThat(response.payload().path("status").asText()).isEqualTo("REJECTED");
    assertThat(response.payload().path("error").path("code").asText())
        .isEqualTo("ACTOR_NOT_AUTHORIZED");
    verify(router, never()).route(request);
  }

  private MessageEnvelope refreshRequest(Actor actor, UUID aggregateId) {
    return new MessageEnvelope(
        UUID.randomUUID(), "RefreshSession", 1, MessageKind.RPC_REQUEST, "bff",
        Instant.now(), UUID.randomUUID(), null, null, null, aggregateId, null,
        actor,
        codec.toJsonNode(new RefreshPayload(UUID.randomUUID(), "secret-refresh-token")));
  }

  private Message message(MessageEnvelope envelope) {
    MessageProperties properties = new MessageProperties();
    properties.setReplyTo("amq.rabbitmq.reply-to");
    return new Message(codec.encode(envelope), properties);
  }

  private record RefreshPayload(UUID sessionId, String refreshToken) {}
}
