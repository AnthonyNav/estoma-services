package com.estoma.washexecution.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import com.estoma.washexecution.contracts.Actor;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.contracts.MessageKind;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EnvelopeValidatorTest {
  private final EnvelopeValidator validator = new EnvelopeValidator();
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void acceptsOperationFreeSystemBffCandidateQuery() {
    assertDoesNotThrow(() -> validator.validateQuery(candidateQuery(null)));
  }

  @Test
  void rejectsClientOperationFieldsOnCandidateQuery() {
    assertThatThrownBy(() -> validator.validateQuery(candidateQuery(UUID.randomUUID())))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_QUERY_ENVELOPE");
  }

  @Test
  void rejectsExtraCandidatePayloadFields() {
    MessageEnvelope query = candidateQuery(null);
    var extra = ((tools.jackson.databind.node.ObjectNode) query.payload().deepCopy())
        .put("unexpected", true);
    MessageEnvelope withExtra = new MessageEnvelope(
        query.messageId(), query.messageType(), query.messageVersion(), query.messageKind(),
        query.producer(), query.occurredAt(), query.correlationId(), query.causationId(),
        query.operationId(), query.idempotencyKey(), query.aggregateId(), query.aggregateVersion(),
        query.actor(), extra);

    assertThatThrownBy(() -> new IntegrationQueryMessageMapper().query(withExtra))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_QUERY_PAYLOAD");
  }

  private MessageEnvelope candidateQuery(UUID operationId) {
    return new MessageEnvelope(
        UUID.randomUUID(), "GetReassignmentCandidates", 1, MessageKind.QUERY, "bff",
        Instant.EPOCH, UUID.randomUUID(), null, operationId, null, null, null,
        new Actor("SYSTEM", null, "bff"),
        json.createObjectNode().put("washExecutionId", UUID.randomUUID().toString()));
  }
}
