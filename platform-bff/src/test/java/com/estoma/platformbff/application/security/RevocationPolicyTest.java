package com.estoma.platformbff.application.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevocationPolicyTest {
  @Test
  void rejectsExactSessionAndPersonCutoff() {
    UUID session = UUID.randomUUID();
    assertThat(RevocationPolicy.rejects(session, 100, true, null, null)).isTrue();
    assertThat(RevocationPolicy.rejects(session, 100, false, 100L, null)).isTrue();
    assertThat(RevocationPolicy.rejects(session, 101, false, 100L, null)).isFalse();
  }

  @Test
  void otherCutoffKeepsOnlyExceptedSession() {
    UUID current = UUID.randomUUID();
    var cutoff = new RevocationPolicy.AccountCutoff(100, false, current);

    assertThat(RevocationPolicy.rejects(current, 100, false, null, cutoff)).isFalse();
    assertThat(RevocationPolicy.rejects(UUID.randomUUID(), 100, false, null, cutoff)).isTrue();
    assertThat(RevocationPolicy.rejects(UUID.randomUUID(), 101, false, null, cutoff)).isFalse();
  }

  @Test
  void allCutoffRejectsEverySessionAtOrBeforeCutoff() {
    var cutoff = new RevocationPolicy.AccountCutoff(100, true, null);
    assertThat(RevocationPolicy.rejects(UUID.randomUUID(), 100, false, null, cutoff)).isTrue();
    assertThat(RevocationPolicy.rejects(UUID.randomUUID(), 99, false, null, cutoff)).isTrue();
    assertThat(RevocationPolicy.rejects(UUID.randomUUID(), 101, false, null, cutoff)).isFalse();
  }
}
