package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class MessageEnvelopeTest {

  @Test
  void v1EnvelopeCarriesIdempotencyAndNeverTargetService() {
    var fields = Arrays.stream(MessageEnvelope.class.getRecordComponents()).map(RecordComponent::getName).toList();

    assertThat(fields).contains("messageId", "messageType", "messageVersion", "messageKind", "producer", "correlationId", "idempotencyKey", "actor", "payload");
    assertThat(fields).doesNotContain("targetService");
  }

  @Test
  void systemActorRetainsTechnicalIdentityAndScope() {
    Actor actor = new Actor("SYSTEM", null, "communications", "GLOBAL", null);

    assertThat(actor.isSystemService("communications")).isTrue();
    assertThat(actor.isSystemService("bff")).isFalse();
  }
}
