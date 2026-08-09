package com.estoma.authentication.infrastructure.messaging.rpc;

import com.estoma.authentication.application.RpcDispatcher;
import com.estoma.authentication.application.RpcOutcome;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
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

  public RpcListener(EnvelopeJsonCodec codec, RpcOperationRouter router, RpcDispatcher dispatcher) {
    this.codec = codec;
    this.router = router;
    this.dispatcher = dispatcher;
  }

  @RabbitListener(queues = MessagingNames.AUTHENTICATION_RPC_QUEUE, containerFactory = "rpcContainerFactory")
  public Message onMessage(Message message) {
    MessageProperties requestProps = message.getMessageProperties();
    String operationType = requestProps.getType();
    UUID correlationId = parseCorrelationId(requestProps.getCorrelationId());

    RpcResponseEnvelope response;
    if (correlationId == null || requestProps.getReplyTo() == null || requestProps.getReplyTo().isBlank()) {
      response = RpcResponseEnvelope.rejected("INVALID_RPC_ENVELOPE");
    } else if (operationType == null || !router.supports(operationType)) {
      log.error("unrecognized RPC operation type={} correlationId={}", operationType, correlationId);
      response = RpcResponseEnvelope.rejected("UNRECOGNIZED_OPERATION");
    } else if (!isAllowedProducer(operationType, requestProps)) {
      response = RpcResponseEnvelope.rejected("PRODUCER_NOT_AUTHORIZED");
    } else {
      response = handle(operationType, correlationId, message.getBody());
    }

    return buildReply(response, requestProps);
  }

  private RpcResponseEnvelope handle(String operationType, UUID correlationId, byte[] body) {
    try {
      RpcOutcome<Object> outcome = dispatcher.execute(correlationId, () -> router.invoke(operationType, body));
      return switch (outcome) {
        case RpcOutcome.Success<Object> success -> RpcResponseEnvelope.succeeded(success.response());
        case RpcOutcome.Rejected<Object> rejected -> RpcResponseEnvelope.rejected(rejected.code().name());
      };
    } catch (Exception e) {
      // Never a DLQ, never a retry (system_contract.md "RPC Security
      // Rules") -- a technical failure just becomes a controlled error
      // response; the BFF's own timeout/failure handling
      // (messaging-architecture.md §6.1) takes it from there.
      log.error("RPC operation {} (correlationId={}) failed technically: {}", operationType, correlationId, e.toString());
      return RpcResponseEnvelope.rejected("TECHNICAL_FAILURE");
    }
  }

  private Message buildReply(RpcResponseEnvelope response, MessageProperties requestProps) {
    MessageProperties replyProps = new MessageProperties();
    replyProps.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    replyProps.setCorrelationId(requestProps.getCorrelationId());
    return new Message(codec.encodeValue(response), replyProps);
  }

  static boolean isAllowedProducer(String operationType, MessageProperties properties) {
    String producer = header(properties, "x-estoma-producer");
    String actorType = header(properties, "x-estoma-actor-type");
    String actorService = header(properties, "x-estoma-actor-service");
    String scope = header(properties, "x-estoma-actor-scope");
    if (AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY.wireName().equals(operationType)) {
      return "communications".equals(producer)
          && "SYSTEM".equals(actorType)
          && "communications".equals(actorService)
          && "GLOBAL".equals(scope);
    }
    return "bff".equals(producer)
        && "ACCOUNT".equals(actorType)
        && "SELF".equals(scope)
        && validSelfTarget(properties);
  }

  private static boolean validSelfTarget(MessageProperties properties) {
    String actorAccountId = header(properties, "x-estoma-actor-account-id");
    String targetAccountId = header(properties, "x-estoma-target-account-id");
    if (actorAccountId == null || targetAccountId == null) {
      return false;
    }
    try {
      return UUID.fromString(actorAccountId).equals(UUID.fromString(targetAccountId));
    } catch (IllegalArgumentException ignored) {
      return false;
    }
  }

  private static String header(MessageProperties properties, String name) {
    Object value = properties.getHeader(name);
    return value instanceof String string ? string : null;
  }

  private static UUID parseCorrelationId(String raw) {
    if (raw != null) {
      try {
        return UUID.fromString(raw);
      } catch (IllegalArgumentException ignored) {
        // fall through to the random fallback below
      }
    }
    log.warn("RPC request missing a valid correlationId");
    return null;
  }
}
