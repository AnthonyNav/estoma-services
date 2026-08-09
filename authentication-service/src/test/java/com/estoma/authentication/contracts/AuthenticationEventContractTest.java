package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticationEventContractTest {

  @Test
  void eventsUseTheDurableEventsExchangeAndHaveNoSecretNamedFact() {
    assertThat(MessagingNames.EVENTS_EXCHANGE).isEqualTo("platform.events");
    assertThat(AuthenticationEvent.values()).extracting(AuthenticationEvent::wireName).noneMatch(name -> name.contains("Token") || name.contains("Code") || name.contains("PasswordHash"));
  }
}
