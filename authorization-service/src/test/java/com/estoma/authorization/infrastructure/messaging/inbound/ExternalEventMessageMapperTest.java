package com.estoma.authorization.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ExternalEventMessageMapperTest {

  private final JsonMapper mapper = JsonMapper.builder().build();
  private final ExternalEventMessageMapper eventMapper =
      new ExternalEventMessageMapper(new EnvelopeJsonCodec(mapper));

  @Test
  void rejectsAnUnsupportedAcademicEligibilitySystemBeforeApplication() {
    UUID eligibilityId = UUID.randomUUID();
    var payload = mapper.createObjectNode()
        .put("eligibilityId", eligibilityId.toString())
        .put("studentAcademicProfileId", UUID.randomUUID().toString())
        .put("accountId", UUID.randomUUID().toString())
        .put("systemCode", "LAVADO_ULTRASONICO")
        .put("eligible", true)
        .put("policyVersion", 1)
        .put("eligibilityVersion", 1)
        .put("academicProfileVersion", 1)
        .put("evaluatedAt", "2026-08-16T00:00:00Z");

    assertThatThrownBy(() -> eventMapper.map(new MessageEnvelope(
            UUID.randomUUID(),
            "StudentAcademicEligibilityChanged",
            1,
            MessageKind.EVENT,
            "academic",
            Instant.parse("2026-08-16T00:00:00Z"),
            UUID.randomUUID(),
            UUID.randomUUID(),
            null,
            null,
            eligibilityId,
            1,
            new WireActor("SYSTEM", null, "academic"),
            payload)))
        .isInstanceOf(InvalidMessageException.class)
        .hasMessage("unsupported academic eligibility systemCode");
  }
}
