package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.communications.application.port.RecoveryCodeDeliveryPort;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

class RabbitRecoveryCodeDeliveryClientTest {

  private final RabbitTemplate rabbit = mock(RabbitTemplate.class);
  private final ObjectMapper mapper = new ObjectMapper();
  private final RabbitRecoveryCodeDeliveryClient client = new RabbitRecoveryCodeDeliveryClient(rabbit, mapper);

  @Test
  void successfulReplyReturnsCodeAndRequestCarriesOnlyRecoveryId() {
    UUID recoveryId=UUID.randomUUID(), correlationId=UUID.randomUUID();
    when(rabbit.sendAndReceive(eq("platform.security.rpc"),eq("rpc.authentication"),any(Message.class)))
        .thenAnswer(invocation -> reply(invocation.getArgument(2),correlationId,"SUCCEEDED","123456",null));

    String code=client.getCode(recoveryId,correlationId);

    assertThat(code).isEqualTo("123456");
    ArgumentCaptor<Message> request=ArgumentCaptor.forClass(Message.class);
    verify(rabbit).sendAndReceive(eq("platform.security.rpc"),eq("rpc.authentication"),request.capture());
    var body=mapper.readTree(request.getValue().getBody());
    assertThat(body.path("messageKind").asString()).isEqualTo("RPC_REQUEST");
    assertThat(body.path("producer").asString()).isEqualTo("communications");
    assertThat(body.path("actor").path("service").asString()).isEqualTo("communications");
    assertThat(body.path("actor").path("scope").asString()).isEqualTo("GLOBAL");
    assertThat(body.path("payload").path("recoveryId").asString()).isEqualTo(recoveryId.toString());
    assertThat(body.toString()).doesNotContain("123456");
  }

  @Test
  void expiredOwnerRejectionMapsToLifecycleFailure() {
    UUID recoveryId=UUID.randomUUID(), correlationId=UUID.randomUUID();
    when(rabbit.sendAndReceive(eq("platform.security.rpc"),eq("rpc.authentication"),any(Message.class)))
        .thenAnswer(invocation -> reply(invocation.getArgument(2),correlationId,"REJECTED",null,"RECOVERY_CODE_EXPIRED"));

    assertThatThrownBy(() -> client.getCode(recoveryId,correlationId))
        .isInstanceOf(RecoveryCodeDeliveryPort.NotAvailableException.class)
        .hasMessage("RECOVERY_EXPIRED");
  }

  @Test
  void timeoutIsTechnicalNotFunctional() {
    when(rabbit.sendAndReceive(eq("platform.security.rpc"),eq("rpc.authentication"),any(Message.class))).thenReturn(null);
    assertThatThrownBy(() -> client.getCode(UUID.randomUUID(),UUID.randomUUID()))
        .isInstanceOf(RecoveryCodeDeliveryPort.TechnicalException.class);
  }

  private Message reply(Message request,UUID correlationId,String status,String code,String errorCode) {
    var requestJson=mapper.readTree(request.getBody());
    ObjectNode root=mapper.createObjectNode();
    root.put("messageId",UUID.randomUUID().toString());
    root.put("messageType","GetRecoveryCodeForDelivery");
    root.put("messageVersion",1);
    root.put("messageKind","RPC_RESPONSE");
    root.put("producer","authentication");
    root.put("occurredAt",java.time.Instant.now().toString());
    root.put("correlationId",correlationId.toString());
    root.put("causationId",requestJson.path("messageId").asString());
    ObjectNode payload=root.putObject("payload");
    payload.put("status",status);
    if (code!=null) payload.putObject("data").put("code",code);
    if (errorCode!=null) payload.putObject("error").put("code",errorCode);
    return new Message(mapper.writeValueAsBytes(root),new MessageProperties());
  }
}
