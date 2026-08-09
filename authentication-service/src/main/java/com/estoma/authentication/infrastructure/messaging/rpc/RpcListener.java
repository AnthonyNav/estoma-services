package com.estoma.authentication.infrastructure.messaging.rpc;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.application.AccountAuthorizationPolicy;
import com.estoma.authentication.contracts.AuthenticationAuthorizationMatrix;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.result.ResultPayload;
import com.estoma.authentication.contracts.rpc.RpcRequestContext;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.authentication.infrastructure.messaging.inbound.EnvelopeValidator;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * The AMQP entry point for ephemeral security RPC. Reply is automatic:
 * Spring AMQP sends whatever this method returns to the inbound
 * message's {@code replyTo} (Direct Reply-To), so there is no manual
 * reply-publishing code here (system_contract.md "RPC Security Rules").
 *
 * <p>No ACK/retry/DLQ handling -- {@code rpcContainerFactory} uses
 * automatic acknowledgement, and a technical failure here becomes a
 * controlled error response rather than a redelivery: the RPC rules
 * forbid both DLQ and late automatic retries for this queue.
 *
 * <p>Never logs the message body: it may contain a password, a recovery
 * code, or a generated temporary password (system_contract.md "RPC
 * Security Rules").
 */
@Component
public class RpcListener {

  private static final Logger log = LoggerFactory.getLogger(RpcListener.class);

  private final EnvelopeJsonCodec codec;
  private final RpcOperationRouter router;
  private final RpcDispatcher dispatcher;
  private final AccountAuthorizationPolicy authorizationPolicy;
  private final EnvelopeValidator envelopeValidator;

  public RpcListener(EnvelopeJsonCodec codec, RpcOperationRouter router, RpcDispatcher dispatcher, AccountAuthorizationPolicy authorizationPolicy, EnvelopeValidator envelopeValidator) {
    this.codec = codec;
    this.router = router;
    this.dispatcher = dispatcher;
    this.authorizationPolicy = authorizationPolicy;
    this.envelopeValidator = envelopeValidator;
  }

  @RabbitListener(queues = MessagingNames.AUTHENTICATION_RPC_QUEUE, containerFactory = "rpcContainerFactory")
  public Message onMessage(Message message) {
    MessageEnvelope request;
    try {
      request = codec.decode(message.getBody());
    } catch (Exception malformed) {
      return buildReply(response(null, ResultPayload.rejected("INVALID_RPC_ENVELOPE")));
    }
    MessageProperties requestProps = message.getMessageProperties();

    try {
      envelopeValidator.validateRpc(request, requestProps.getReplyTo());
    } catch (Exception invalid) {
      return buildReply(response(request, ResultPayload.rejected("INVALID_RPC_ENVELOPE")));
    }

    return buildReply(response(request, handle(request)));
  }

  private ResultPayload handle(MessageEnvelope request) {
    try {
      RpcRequestContext context = RpcRequestContext.from(request);
      RpcOutcome<Object> outcome = dispatcher.execute(
              context, () -> {
              authorizationPolicy.check(request);
              return router.invoke(request.messageType(), codec.encodeValue(request.payload()), context);
            });
      return switch (outcome) {
        case RpcOutcome.Success<Object> success -> ResultPayload.succeeded(success.response());
        case RpcOutcome.Rejected<Object> rejected -> ResultPayload.rejected(rejected.code().name());
      };
    } catch (Exception e) {
      // Never a DLQ, never a retry (system_contract.md "RPC Security
      // Rules") -- a technical failure just becomes a controlled error
      // response; the BFF's own timeout/failure handling
      // (messaging-architecture.md §6.1) takes it from there.
      log.error("RPC operation {} (correlationId={}) failed technically: {}", request.messageType(), request.correlationId(), e.toString());
      return ResultPayload.failed("TECHNICAL_FAILURE");
    }
  }

  private MessageEnvelope response(MessageEnvelope request, ResultPayload result) {
    if (request == null) {
      return new MessageEnvelope(
          UUID.randomUUID(), "RPC", 1, MessageKind.RPC_RESPONSE, "authentication", Instant.now(),
          UUID.randomUUID(), null, null, null, null, null, null, codec.toJsonNode(result));
    }
    return new MessageEnvelope(
        UUID.randomUUID(), request.messageType(), 1, MessageKind.RPC_RESPONSE, "authentication", Instant.now(),
        request.correlationId(), request.messageId(), null, null, request.aggregateId(), null, request.actor(), codec.toJsonNode(result));
  }

  private Message buildReply(MessageEnvelope response) {
    MessageProperties replyProps = new MessageProperties();
    replyProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    return new Message(codec.encode(response), replyProps);
  }

  static boolean isAllowedProducer(MessageEnvelope request) {
    return AuthenticationAuthorizationMatrix.permits(request);
  }
}
