package com.estoma.academic.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AcademicQueryEnvelopeValidatorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AcademicQueryEnvelopeValidator validator = new AcademicQueryEnvelopeValidator();

  @Test
  void acceptsEligibilitySnapshotQueryFromAuthorization() {
    assertThatCode(() -> validator.validate(query(
        "GetStudentAcademicEligibilitySnapshot",
        mapper.createObjectNode()
            .put("accountId", UUID.randomUUID().toString())
            .put("systemCode", "PRACTICAS_PROFESIONALES"))))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAQueryThatCanTargetTwoEligibilityIdentities() {
    var payload = mapper.createObjectNode()
        .put("accountId", UUID.randomUUID().toString())
        .put("studentAcademicProfileId", UUID.randomUUID().toString())
        .put("systemCode", "PRACTICAS_PROFESIONALES");
    assertThatThrownBy(() -> validator.validate(query("GetStudentAcademicEligibilitySnapshot", payload)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_ELIGIBILITY_TARGET");
  }

  @Test
  void rejectsAProducerOrActorOutsideTheRecoveryAcl() {
    MessageEnvelope query = query("ListStudentAcademicEligibilitySnapshotsPage",
        mapper.createObjectNode().put("limit", 100));
    MessageEnvelope wrongProducer = new MessageEnvelope(query.messageId(), query.messageType(), query.messageVersion(),
        query.messageKind(), "academic", query.occurredAt(), query.correlationId(), query.causationId(),
        query.operationId(), null, null, null, query.actor(), query.payload());
    assertThatThrownBy(() -> validator.validate(wrongProducer))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("QUERY_PRODUCER_NOT_ALLOWED");

    MessageEnvelope wrongActor = new MessageEnvelope(query.messageId(), query.messageType(), query.messageVersion(),
        query.messageKind(), query.producer(), query.occurredAt(), query.correlationId(), query.causationId(),
        query.operationId(), null, null, null, new Actor("SYSTEM", null, "academic"), query.payload());
    assertThatThrownBy(() -> validator.validate(wrongActor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("QUERY_ACTOR_NOT_ALLOWED");
  }

  @Test
  void validatesCursorAndLimitForFullRecovery() {
    assertThatCode(() -> validator.validate(query("ListStudentAcademicEligibilitySnapshotsPage",
        mapper.createObjectNode().put("limit", 100)
            .put("cursor", UUID.randomUUID().toString())
            .put("systemCode", "PRACTICAS_PROFESIONALES"))))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validate(query("ListStudentAcademicEligibilitySnapshotsPage",
        mapper.createObjectNode().put("limit", 0))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_LIMIT");
    assertThatThrownBy(() -> validator.validate(query("ListStudentAcademicEligibilitySnapshotsPage",
        mapper.createObjectNode().put("limit", 10).put("cursor", "not-a-uuid"))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("INVALID_CURSOR");
  }

  private MessageEnvelope query(String type, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(UUID.randomUUID(), type, 1, "QUERY", "authorization", Instant.now(),
        UUID.randomUUID(), null, UUID.randomUUID(), null, null, null,
        new Actor("SYSTEM", null, "authorization"), payload);
  }
}
