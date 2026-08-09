package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class RequestFingerprintTest {
  @Test
  void isStableSha256ForCanonicalInput() {
    String first = RequestFingerprint.sha256("recipient", "system", "type", "source", "event", "1", "EMAIL", "body");
    String second = RequestFingerprint.sha256("recipient", "system", "type", "source", "event", "1", "EMAIL", "body");
    assertThat(first).hasSize(64).isEqualTo(second);
  }

  @Test
  void changesWhenAFunctionalFieldChanges() {
    assertThat(RequestFingerprint.sha256("recipient", "system", "type", "source", "event", "1", "EMAIL", "body"))
        .isNotEqualTo(RequestFingerprint.sha256("recipient", "system", "type", "source", "event", "1", "INBOX", "body"));
  }
}
