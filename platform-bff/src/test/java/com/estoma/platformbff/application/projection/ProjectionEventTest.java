package com.estoma.platformbff.application.projection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProjectionEventTest {
  @Test
  void payloadPreservesExplicitNullValuesInAnImmutableSnapshot() {
    Map<String, Object> source = new HashMap<>();
    source.put("preferredName", null);
    source.put("active", true);

    ProjectionEvent event = new ProjectionEvent(
        UUID.randomUUID(), "PersonUpdated", 1, "users", Instant.now(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, source);

    source.put("preferredName", "changed-after-construction");

    assertThat(event.payload())
        .containsEntry("preferredName", null)
        .containsEntry("active", true);
    assertThatThrownBy(() -> event.payload().put("newField", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void nullPayloadBecomesAnEmptyImmutableMap() {
    ProjectionEvent event = new ProjectionEvent(
        UUID.randomUUID(), "PersonUpdated", 1, "users", Instant.now(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), 2, null);

    assertThat(event.payload()).isEmpty();
    assertThatThrownBy(() -> event.payload().put("newField", "value"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
