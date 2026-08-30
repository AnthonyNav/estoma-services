package com.estoma.authorization.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.estoma.authorization.application.query.AuthorizationSnapshotQuery.ListAccountAuthorizationSnapshotsPage;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class EnvelopeValidatorTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final EnvelopeValidator validator = new EnvelopeValidator(codec);

  @Test
  void acceptsOptionalIdempotencyKeyOnCommandResults() {
    var payload = JsonMapper.builder().build().createObjectNode().put("status", "SUCCEEDED");
    MessageEnvelope result = new MessageEnvelope(
        UUID.randomUUID(),
        "AssignAccountRoleResult",
        1,
        MessageKind.RESULT,
        "authorization",
        Instant.parse("2026-08-16T00:00:00Z"),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new WireActor("SYSTEM", null, "authorization"),
        payload);

    assertThatCode(() -> validator.validate(
        codec.readTree(codec.encode(result)), MessageKind.RESULT, Set.of("AssignAccountRoleResult")))
        .doesNotThrowAnyException();
  }

  @Test
  void treatsOptionalQueryFieldsEncodedAsNullAsAbsent() {
    var payload = JsonMapper.builder().build().createObjectNode()
        .put("limit", 100)
        .putNull("systemCode")
        .putNull("cursor");
    MessageEnvelope query = new MessageEnvelope(
        UUID.randomUUID(),
        "ListAccountAuthorizationSnapshotsPage",
        1,
        MessageKind.QUERY,
        "wash-administration",
        Instant.parse("2026-08-16T00:00:00Z"),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        null,
        null,
        null,
        new WireActor("SYSTEM", null, "wash-administration"),
        payload);
    ObjectNode raw = (ObjectNode) codec.readTree(codec.encode(query));
    raw.putNull("causationId");
    raw.putNull("idempotencyKey");
    raw.putNull("aggregateId");
    raw.putNull("aggregateVersion");

    MessageEnvelope validated = validator.validate(
        raw, MessageKind.QUERY, Set.of("ListAccountAuthorizationSnapshotsPage"));
    QueryMessageMapper.MappedQuery mapped = new QueryMessageMapper().map(validated);

    assertThat(mapped.query()).isInstanceOfSatisfying(
        ListAccountAuthorizationSnapshotsPage.class,
        page -> {
          assertThat(page.cursor()).isNull();
          assertThat(page.limit()).isEqualTo(100);
          assertThat(page.systemCode()).isNull();
        });
  }
}
