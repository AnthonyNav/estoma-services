package com.estoma.authentication.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.AuthenticationCommand;
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
}
