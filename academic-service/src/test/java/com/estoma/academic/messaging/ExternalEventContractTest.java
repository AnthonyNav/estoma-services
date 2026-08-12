package com.estoma.academic.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.academic.contract.MessageEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ExternalEventContractTest {
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void acceptsOnlyTheProducerThatOwnsTheEvent() {
    assertThatCode(() -> ExternalEventContract.validate(event("PersonCreated", "users")))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> ExternalEventContract.validate(event("PersonCreated", "authentication")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsUnknownEventTypesInsteadOfMarkingThemApplied() {
    assertThatThrownBy(() -> ExternalEventContract.validate(event("UnrelatedEvent", "users")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private MessageEnvelope event(String type, String producer) {
    return new MessageEnvelope(UUID.randomUUID(), type, 1, "EVENT", producer, Instant.now(),
        UUID.randomUUID(), null, null, null, UUID.randomUUID(), 1, null,
        mapper.createObjectNode());
  }
}
