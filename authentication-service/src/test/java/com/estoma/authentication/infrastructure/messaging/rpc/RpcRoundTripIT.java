package com.estoma.authentication.infrastructure.messaging.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.result.ResultStatus;
import com.estoma.authentication.contracts.rpc.AuthenticateAccountRequest;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.testsupport.AbstractRabbitMqIT;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end proof that {@code RpcListener} correctly wires {@link
 * RpcOperationRouter}/{@code RpcDispatcher} to real RabbitMQ Direct
 * Reply-To: {@link RabbitTemplate#sendAndReceive} blocks on the broker's
 * {@code amq.rabbitmq.reply-to} pseudo-queue exactly as the BFF Security
 * RPC Gateway would.
 */
class RpcRoundTripIT extends AbstractRabbitMqIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private EnvelopeJsonCodec codec;

  @Test
  void authenticateAccountRoundTripsAndRejectsAnUnknownIdentifier() {
    var requestPayload =
        new AuthenticateAccountRequest(LoginType.MATRICULA, "no-such-login-" + System.nanoTime(), "irrelevant-password", null, null, null);

    MessageEnvelope request = new MessageEnvelope(
        java.util.UUID.randomUUID(), AuthenticationRpcOperation.AUTHENTICATE_ACCOUNT.wireName(), 1, MessageKind.RPC_REQUEST,
        "bff", java.time.Instant.now(), java.util.UUID.randomUUID(), null, null, null, null, null,
        new Actor("ANONYMOUS", null, null, "PUBLIC", null), codec.toJsonNode(requestPayload));
    MessageProperties requestProps = new MessageProperties();
    requestProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    Message requestMessage = new Message(codec.encode(request), requestProps);

    Message reply = rabbitTemplate.sendAndReceive(MessagingNames.SECURITY_RPC_EXCHANGE, MessagingNames.AUTHENTICATION_RPC_QUEUE, requestMessage);

    assertThat(reply).isNotNull();
    MessageEnvelope response = codec.decode(reply.getBody());
    assertThat(response.messageKind()).isEqualTo(MessageKind.RPC_RESPONSE);
    assertThat(response.correlationId()).isEqualTo(request.correlationId());
    assertThat(response.causationId()).isEqualTo(request.messageId());
    assertThat(codec.convertPayload(response, ResultPayload.class).status()).isEqualTo(ResultStatus.REJECTED);
  }
}
