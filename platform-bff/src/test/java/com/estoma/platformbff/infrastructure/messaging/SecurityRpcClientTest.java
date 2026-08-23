package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.security.SecurityRpcUnavailableException;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.ResultEnvelope;
import com.estoma.platformbff.infrastructure.config.MessagingTopology;
import com.estoma.platformbff.infrastructure.config.PlatformProperties;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class SecurityRpcClientTest {
  private final JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();
  private final MessageEnvelopeCodec codec = new MessageEnvelopeCodec(mapper);
  private final PlatformProperties properties = new PlatformProperties(
      "bff",
      new PlatformProperties.SecurityRpc(Duration.ofSeconds(10), Duration.ofSeconds(8), 0),
      new PlatformProperties.Jwt("issuer", "audience", ""),
      new PlatformProperties.Retention(7, 24, 7, 30, 30, 30));

  @Test
  void validatesCorrelationCausationProducerVersionAndPayload() {
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    UUID accountId = UUID.randomUUID();
    when(rabbit.sendAndReceive(
        eq(MessagingTopology.SECURITY_RPC_EXCHANGE),
        eq(MessagingTopology.AUTHENTICATION_RPC_QUEUE),
        any(Message.class)))
        .thenAnswer(invocation -> {
          Message sent = invocation.getArgument(2);
          JsonNode request = mapper.readValue(sent.getBody(), JsonNode.class);
          JsonNode data = mapper.createObjectNode().put("revoked", true);
          JsonNode payload = mapper.createObjectNode().put("status", "SUCCEEDED").set("data", data);
          var response = new ResultEnvelope(
              UUID.randomUUID(), "RevokeSession", 1, MessageKind.RPC_RESPONSE,
              "authentication", Instant.now(),
              UUID.fromString(request.path("correlationId").asText()),
              UUID.fromString(request.path("messageId").asText()),
              null, null, accountId, null,
              ActorContext.account(accountId, "SELF", null), payload);
          return new Message(codec.write(response).getBytes(StandardCharsets.UTF_8), new MessageProperties());
        });
    var client = new SecurityRpcClient(rabbit, codec, properties);

    var result = client.call(
        "RevokeSession", ActorContext.account(accountId, "SELF", null), accountId,
        null, "{\"sessionId\":\"" + UUID.randomUUID() + "\"}");

    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.dataJson()).contains("\"revoked\":true");
    var sent = ArgumentCaptor.forClass(Message.class);
    verify(rabbit).sendAndReceive(
        eq(MessagingTopology.SECURITY_RPC_EXCHANGE),
        eq(MessagingTopology.AUTHENTICATION_RPC_QUEUE), sent.capture());
    assertThat(sent.getValue().getMessageProperties().getExpiration()).isEqualTo("10000");
  }

  @Test
  void wrongCausationIsProtocolFailure() {
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    when(rabbit.sendAndReceive(any(String.class), any(String.class), any(Message.class)))
        .thenAnswer(invocation -> {
          Message sent = invocation.getArgument(2);
          JsonNode request = mapper.readValue(sent.getBody(), JsonNode.class);
          JsonNode payload = mapper.createObjectNode()
              .put("status", "SUCCEEDED")
              .set("data", mapper.createObjectNode().put("accessToken", "x"));
          var response = new ResultEnvelope(
              UUID.randomUUID(), "AuthenticateAccount", 1, MessageKind.RPC_RESPONSE,
              "authentication", Instant.now(),
              UUID.fromString(request.path("correlationId").asText()), UUID.randomUUID(),
              null, null, null, null, ActorContext.anonymous(), payload);
          return new Message(codec.write(response).getBytes(StandardCharsets.UTF_8), new MessageProperties());
        });
    var client = new SecurityRpcClient(rabbit, codec, properties);

    assertThatThrownBy(() -> client.call(
        "AuthenticateAccount", ActorContext.anonymous(), null, null,
        "{\"loginType\":\"USERNAME\"}"))
        .isInstanceOf(SecurityRpcUnavailableException.class);
  }

  @Test
  void timeoutBecomesControlledUnavailableWithoutAutomaticRetry() {
    RabbitTemplate rabbit = org.mockito.Mockito.mock(RabbitTemplate.class);
    when(rabbit.sendAndReceive(any(String.class), any(String.class), any(Message.class)))
        .thenReturn(null);
    var client = new SecurityRpcClient(rabbit, codec, properties);

    assertThatThrownBy(() -> client.call(
        "AuthenticateAccount", ActorContext.anonymous(), null, null,
        "{\"password\":\"never-logged\"}"))
        .isInstanceOf(SecurityRpcUnavailableException.class);
    verify(rabbit, times(1)).sendAndReceive(
        eq(MessagingTopology.SECURITY_RPC_EXCHANGE),
        eq(MessagingTopology.AUTHENTICATION_RPC_QUEUE), any(Message.class));
  }
}
