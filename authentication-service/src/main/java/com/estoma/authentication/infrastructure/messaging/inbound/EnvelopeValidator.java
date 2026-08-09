package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Structural and contractual validation of an inbound envelope, run
 * before any business processing. Anything this rejects is a permanent
 * failure and goes straight to the DLQ, never a retry tier. Deliberately
 * NOT domain validation -- that happens inside the transactional
 * dispatcher and produces a functional rejection, not a DLQ'd message.
 *
 * <p>Routing information is not part of the envelope contract.
 */
@Component
public class EnvelopeValidator {

  private static final int SUPPORTED_MESSAGE_VERSION = 1;

  private static final Set<String> COMMAND_MESSAGE_TYPES =
      Arrays.stream(AuthenticationCommand.values()).map(AuthenticationCommand::wireName).collect(Collectors.toSet());

  public void validateCommand(MessageEnvelope envelope) {
    validateCommon(envelope);
    if (envelope.messageKind() != MessageKind.COMMAND) {
      throw new InvalidMessageException(
           "expected messageKind=COMMAND on commands.authentication, got " + envelope.messageKind());
    }
    if (!COMMAND_MESSAGE_TYPES.contains(envelope.messageType())) {
      throw new InvalidMessageException("unrecognized command messageType " + envelope.messageType());
    }
    requireNonBlank(envelope.idempotencyKey(), "idempotencyKey");
  }

  public void validateEvent(MessageEnvelope envelope) {
    validateCommon(envelope);
    if (envelope.messageKind() != MessageKind.EVENT) {
      throw new InvalidMessageException(
           "expected messageKind=EVENT on events.authentication, got " + envelope.messageKind());
    }
    // messageType is not checked against a closed set here: this queue
    // deliberately accepts events from producers this service does not
    // yet fully control (academic-service's not-yet-implemented
    // StudentNumberCorrected publisher, see AmqpNames) -- an unrecognized
    // messageType from an unexpected producer becomes
    // ProjectionDispatcher's "no handler registered" IllegalStateException
    // instead, which is a technical (retryable-until-DLQ) failure rather
    // than an immediate DLQ, so an operator has a window to react.
  }

  public void validateRpc(MessageEnvelope envelope, String replyTo) {
    validateCommon(envelope);
    if (envelope.messageKind() != MessageKind.RPC_REQUEST || envelope.operationId() != null
        || envelope.payload() == null || replyTo == null || replyTo.isBlank()) {
      throw new InvalidMessageException("invalid RPC envelope structure");
    }
    if (Arrays.stream(AuthenticationRpcOperation.values()).noneMatch(operation -> operation.wireName().equals(envelope.messageType()))) {
      throw new InvalidMessageException("unrecognized RPC messageType " + envelope.messageType());
    }
    validateActor(envelope.actor());
    if ((AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD.wireName().equals(envelope.messageType())
        || AuthenticationRpcOperation.REGENERATE_TEMPORARY_PASSWORD.wireName().equals(envelope.messageType()))
        && (envelope.idempotencyKey() == null || envelope.idempotencyKey().isBlank())) {
      throw new InvalidMessageException("idempotencyKey must not be blank for temporary-password RPC");
    }
  }

  private static void validateActor(Actor actor) {
    if (actor == null || actor.type() == null || actor.scope() == null || actor.scope().isBlank()) {
      throw new InvalidMessageException("actor is invalid");
    }
    switch (actor.type()) {
      case "ANONYMOUS" -> {
        if (actor.accountId() != null || actor.service() != null || actor.systemId() != null) {
          throw new InvalidMessageException("anonymous actor is invalid");
        }
      }
      case "ACCOUNT" -> {
        if (actor.accountId() == null || actor.service() != null) {
          throw new InvalidMessageException("account actor is invalid");
        }
      }
      case "SYSTEM" -> {
        if (actor.accountId() != null || actor.service() == null || actor.service().isBlank()) {
          throw new InvalidMessageException("system actor is invalid");
        }
      }
      default -> throw new InvalidMessageException("unrecognized actor type " + actor.type());
    }
  }

  private void validateCommon(MessageEnvelope envelope) {
    requireNonNull(envelope, "envelope");
    requireNonNull(envelope.messageId(), "messageId");
    requireNonNull(envelope.correlationId(), "correlationId");
    requireNonNull(envelope.occurredAt(), "occurredAt");
    requireNonBlank(envelope.messageType(), "messageType");
    requireNonBlank(envelope.producer(), "producer");
    requireNonNull(envelope.messageKind(), "messageKind");
    if (envelope.messageVersion() != SUPPORTED_MESSAGE_VERSION) {
      throw new InvalidMessageException(
          "unsupported messageVersion " + envelope.messageVersion() + " for " + envelope.messageType());
    }
  }

  private static void requireNonNull(Object value, String fieldName) {
    if (value == null) {
      throw new InvalidMessageException(fieldName + " must not be null");
    }
  }

  private static void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new InvalidMessageException(fieldName + " must not be blank");
    }
  }
}
