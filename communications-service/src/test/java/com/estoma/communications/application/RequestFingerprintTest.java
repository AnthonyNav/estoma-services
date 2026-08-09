package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.contracts.CommunicationPriority;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.contracts.command.RequestCommunicationCommand;
import java.util.List;
import java.util.UUID;
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

  @Test
  void normalizesEquivalentJsonTemplateData() {
    assertThat(RequestFingerprint.sha256("{\"first\":1,\"second\":2}"))
        .isEqualTo(RequestFingerprint.sha256("{\"second\":2,\"first\":1}"));
  }

  @Test
  void commandFingerprintNormalizesTemplateDataJsonKeyOrder() {
    UUID recipient = UUID.randomUUID();
    assertThat(RequestFingerprint.forCommand(templateCommand(recipient, CommunicationPriority.NORMAL, "{\"first\":1,\"nested\":{\"a\":2,\"b\":3}}"), List.of("EMAIL")))
        .isEqualTo(RequestFingerprint.forCommand(templateCommand(recipient, CommunicationPriority.NORMAL, "{\"nested\":{\"b\":3,\"a\":2},\"first\":1}"), List.of("EMAIL")));
  }

  @Test
  void commandFingerprintChangesWhenPriorityChanges() {
    UUID recipient = UUID.randomUUID();
    assertThat(RequestFingerprint.forCommand(templateCommand(recipient, CommunicationPriority.NORMAL, "{}"), List.of("EMAIL")))
        .isNotEqualTo(RequestFingerprint.forCommand(templateCommand(recipient, CommunicationPriority.CRITICAL, "{}"), List.of("EMAIL")));
  }

  private RequestCommunicationCommand templateCommand(UUID recipient, CommunicationPriority priority, String templateData) {
    return new RequestCommunicationCommand(recipient, "system", "type", priority, "source", "reference", "id", null, null,
        new RequestCommunicationCommand.EmailContent(EmailContentMode.TEMPLATE, "sender", null, null, null, "template", templateData));
  }
}
