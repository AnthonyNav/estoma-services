package com.estoma.authentication.infrastructure.messaging.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessagingNames;
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

    MessageProperties requestProps = new MessageProperties();
    requestProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    requestProps.setType("AuthenticateAccount");
    Message requestMessage = new Message(codec.encodeValue(requestPayload), requestProps);

    Message reply = rabbitTemplate.sendAndReceive(MessagingNames.SECURITY_RPC_EXCHANGE, MessagingNames.AUTHENTICATION_RPC_QUEUE, requestMessage);

    assertThat(reply).isNotNull();
    RpcResponseEnvelope response = codec.decodeValue(reply.getBody(), RpcResponseEnvelope.class);
    assertThat(response.status()).isEqualTo("REJECTED");
  }
}
