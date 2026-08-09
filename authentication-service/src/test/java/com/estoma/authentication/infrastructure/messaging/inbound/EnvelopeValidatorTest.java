package com.estoma.authentication.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.Actor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EnvelopeValidatorTest {

  private final EnvelopeValidator validator = new EnvelopeValidator();

  @Test
  void rejectsAnOperationOutsideTheAuthenticationCommandContract() {
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), "DeleteAccount", 1, MessageKind.COMMAND, "bff", Instant.now(), UUID.randomUUID(), null,
        UUID.randomUUID(), "test", UUID.randomUUID(), 1, null, null);

    assertThatThrownBy(() -> validator.validateCommand(envelope)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void requiresAnIdempotencyKeyForEveryV1Command() {
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(), 1, MessageKind.COMMAND, "bff",
        Instant.now(), UUID.randomUUID(), null, UUID.randomUUID(), null, null, null, null, null);

    assertThatThrownBy(() -> validator.validateCommand(envelope)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void requiresAnIdempotencyKeyForTemporaryPasswordRpc() {
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD.wireName(), 1, MessageKind.RPC_REQUEST, "bff",
        Instant.now(), UUID.randomUUID(), null, null, null, UUID.randomUUID(), null,
        new Actor("ACCOUNT", UUID.randomUUID(), null, "GLOBAL", null),
        new tools.jackson.databind.ObjectMapper().createObjectNode());

    assertThatThrownBy(() -> validator.validateRpc(envelope, "amq.rabbitmq.reply-to"))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void acceptsAValidTemporaryPasswordRpcEnvelope() {
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD.wireName(), 1, MessageKind.RPC_REQUEST, "bff",
        Instant.now(), UUID.randomUUID(), null, null, "idempotency-key", UUID.randomUUID(), null,
        new Actor("ACCOUNT", UUID.randomUUID(), null, "GLOBAL", null),
        new tools.jackson.databind.ObjectMapper().createObjectNode());

    envelope = new MessageEnvelope(
        envelope.messageId(), envelope.messageType(), envelope.messageVersion(), envelope.messageKind(), envelope.producer(), envelope.occurredAt(),
        envelope.correlationId(), envelope.causationId(), envelope.operationId(), envelope.idempotencyKey(), envelope.aggregateId(), envelope.aggregateVersion(),
        new Actor("ACCOUNT", envelope.aggregateId(), null, "GLOBAL", null), envelope.payload());

    validator.validateRpc(envelope, "amq.rabbitmq.reply-to");
  }
}
