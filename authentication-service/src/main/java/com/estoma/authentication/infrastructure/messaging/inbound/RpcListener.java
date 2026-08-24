package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.contracts.AuthenticationAuthorizationMatrix;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.infrastructure.messaging.AmqpNames;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Ephemeral Security RPC adapter for {@code rpc.authentication}. Responses
 * travel only through RabbitMQ Direct Reply-To; they are never persisted or
 * published to platform.results.
 */
@Component
public class RpcListener {
  private static final Logger log = LoggerFactory.getLogger(RpcListener.class);

  private final EnvelopeJsonCodec codec;
  private final EnvelopeValidator validator;
  private final RpcOperationRouter router;

  public RpcListener(
      EnvelopeJsonCodec codec,
      EnvelopeValidator validator,
      RpcOperationRouter router) {
    this.codec = codec;
    this.validator = validator;
    this.router = router;
  }

  @RabbitListener(
      id = "rpcListener",
      queues = MessagingNames.AUTHENTICATION_RPC_QUEUE,
      containerFactory = "rpcContainerFactory")
  public byte[] onMessage(Message message) {
    MessageEnvelope envelope;
    try {
      envelope = codec.decode(message.getBody());
    } catch (RuntimeException malformed) {
      log.warn("discarded malformed security RPC request category=decode");
      throw new AmqpRejectAndDontRequeueException("invalid security RPC request", malformed);
    }

    try {
      validator.validateRpc(envelope, message.getMessageProperties().getReplyTo());
    } catch (RuntimeException invalid) {
      log.warn(
          "rejected invalid security RPC envelope messageId={} operation={} category=validation",
          envelope.messageId(), envelope.messageType());
      if (canReply(envelope, message)) {
        return codec.encode(response(envelope, "FAILED", null, "INVALID_RPC_REQUEST"));
      }
      throw new AmqpRejectAndDontRequeueException("invalid security RPC envelope", invalid);
    }

    AuthenticationAuthorizationMatrix.Decision decision =
        AuthenticationAuthorizationMatrix.decision(envelope);
    if (decision != AuthenticationAuthorizationMatrix.Decision.AUTHORIZED) {
      RejectionCode code = decision == AuthenticationAuthorizationMatrix.Decision.PRODUCER_NOT_AUTHORIZED
          ? RejectionCode.PRODUCER_NOT_AUTHORIZED
          : RejectionCode.ACTOR_NOT_AUTHORIZED;
      return codec.encode(response(envelope, "REJECTED", null, code.name()));
    }
    if (!AuthenticationAuthorizationMatrix.payloadMatchesAggregate(envelope)) {
      return codec.encode(response(
          envelope, "REJECTED", null, RejectionCode.ACTOR_NOT_AUTHORIZED.name()));
    }

    try {
      RpcOutcome<?> outcome = router.route(envelope);
      return codec.encode(switch (outcome) {
        case RpcOutcome.Success<?> success ->
            response(envelope, "SUCCEEDED", success.response(), null);
        case RpcOutcome.Rejected<?> rejected ->
            response(envelope, "REJECTED", null, rejected.code().name());
      });
    } catch (RuntimeException technicalFailure) {
      log.warn(
          "security RPC processing failed messageId={} operation={} category=technical",
          envelope.messageId(), envelope.messageType());
      throw new AmqpRejectAndDontRequeueException(
          "security RPC processing failed", technicalFailure);
    }
  }

  private MessageEnvelope response(
      MessageEnvelope request, String status, Object data, String errorCode) {
    Object payload = "SUCCEEDED".equals(status)
        ? new SuccessPayload(status, data, null)
        : new FailurePayload(status, null, new ErrorPayload(errorCode));
    return new MessageEnvelope(
        UUID.randomUUID(),
        request.messageType(),
        1,
        MessageKind.RPC_RESPONSE,
        AmqpNames.PRODUCER,
        Instant.now(),
        request.correlationId(),
        request.messageId(),
        null,
        null,
        request.aggregateId(),
        request.aggregateVersion(),
        request.actor(),
        codec.toJsonNode(payload));
  }

  private static boolean canReply(MessageEnvelope envelope, Message message) {
    return envelope != null
        && envelope.messageId() != null
        && envelope.correlationId() != null
        && envelope.messageType() != null
        && !envelope.messageType().isBlank()
        && message.getMessageProperties().getReplyTo() != null
        && !message.getMessageProperties().getReplyTo().isBlank();
  }

  private record SuccessPayload(String status, Object data, Object error) {}
  private record FailurePayload(String status, Object data, ErrorPayload error) {}
  private record ErrorPayload(String code) {}
}
