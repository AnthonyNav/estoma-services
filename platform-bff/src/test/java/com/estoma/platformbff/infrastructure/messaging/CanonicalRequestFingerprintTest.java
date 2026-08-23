package com.estoma.platformbff.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.contracts.messaging.ActorContext;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class CanonicalRequestFingerprintTest {
  private final CanonicalRequestFingerprint fingerprints =
      new CanonicalRequestFingerprint(JsonMapper.builder().build());
  private final UUID accountId = UUID.randomUUID();
  private final ActorContext actor = ActorContext.account(accountId, "GLOBAL", null);

  @Test
  void objectKeyOrderDoesNotChangeFingerprint() {
    UUID aggregateId = UUID.randomUUID();
    var left = fingerprints.normalize(
        "create", "CreatePerson", "{\"b\":2,\"a\":1}", aggregateId, 3, actor);
    var right = fingerprints.normalize(
        "create", "CreatePerson", "{\"a\":1,\"b\":2}", aggregateId, 3, actor);

    assertThat(left.fingerprint()).isEqualTo(right.fingerprint());
    assertThat(left.canonicalPayloadJson()).isEqualTo("{\"a\":1,\"b\":2}");
  }

  @Test
  void arrayOrderIsMeaningful() {
    var left = fingerprints.normalize(
        "create", "CreatePerson", "{\"items\":[1,2]}", null, null, actor);
    var right = fingerprints.normalize(
        "create", "CreatePerson", "{\"items\":[2,1]}", null, null, actor);

    assertThat(left.fingerprint()).isNotEqualTo(right.fingerprint());
  }

  @Test
  void aggregateIdentityAndVersionAreFunctionalFingerprintMaterial() {
    UUID firstAggregate = UUID.randomUUID();
    UUID secondAggregate = UUID.randomUUID();
    var base = fingerprints.normalize("update", "UpdatePerson", "{}", firstAggregate, 4, actor);
    var otherAggregate = fingerprints.normalize("update", "UpdatePerson", "{}", secondAggregate, 4, actor);
    var otherVersion = fingerprints.normalize("update", "UpdatePerson", "{}", firstAggregate, 5, actor);

    assertThat(base.fingerprint()).isNotEqualTo(otherAggregate.fingerprint());
    assertThat(base.fingerprint()).isNotEqualTo(otherVersion.fingerprint());
  }

  @Test
  void actorScopeIsFunctionalFingerprintMaterial() {
    UUID aggregateId = UUID.randomUUID();
    var global = fingerprints.normalize(
        "update", "UpdatePerson", "{}", aggregateId, 1,
        ActorContext.account(accountId, "GLOBAL", null));
    var personnel = fingerprints.normalize(
        "update", "UpdatePerson", "{}", aggregateId, 1,
        ActorContext.account(accountId, "SYSTEM_PERSONNEL", "wash"));

    assertThat(global.fingerprint()).isNotEqualTo(personnel.fingerprint());
  }
}
