package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CommunicationsEventRoutingKeyResolverTest {

  private final CommunicationsEventRoutingKeyResolver resolver = new CommunicationsEventRoutingKeyResolver();

  @Test
  void resolvesRepresentativeCanonicalKeys() {
    assertThat(resolver.resolve("CommunicationRequested")).isEqualTo("communications.communication.requested");
    assertThat(resolver.resolve("InboxMessageCreated")).isEqualTo("communications.inbox-message.created");
    assertThat(resolver.resolve("EmailDeliveryFailed")).isEqualTo("communications.email-delivery.failed");
  }

  @Test
  void rejectsUnknownEventTypes() {
    assertThatThrownBy(() -> resolver.resolve("UnknownCommunicationEvent"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
