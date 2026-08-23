package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.users.contract.MessageTypes;
import com.estoma.users.messaging.AmqpNames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoutingKeyResolverTest {

  private final RoutingKeyResolver resolver = new RoutingKeyResolver();

  @Test
  void eventRoutingKeysCoverExactlyEveryEventMessageType() {
    assertThat(RoutingKeyResolver.eventRoutingKeys().keySet())
        .containsExactlyInAnyOrderElementsOf(MessageTypes.EVENTS);
  }

  @ParameterizedTest
  @CsvSource({
    "PersonCreated, users.person.created",
    "PersonUpdated, users.person.updated",
    "PersonActivated, users.person.activated",
    "PersonDeactivated, users.person.deactivated",
  })
  void resolveEventReturnsCanonicalUsersRoutingKey(String messageType, String expectedKey) {
    RoutingKeyResolver.Destination destination = resolver.resolveEvent(messageType);
    assertThat(destination.exchange()).isEqualTo(AmqpNames.EXCHANGE_EVENTS);
    assertThat(destination.routingKey()).isEqualTo(expectedKey);
  }

  @Test
  void resolveEventRejectsAnUnknownMessageType() {
    assertThatThrownBy(() -> resolver.resolveEvent("SomethingNotAnEvent"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolveResultUsesTheResultsExchangeAndTheGivenDestination() {
    RoutingKeyResolver.Destination destination = resolver.resolveResult("bff");
    assertThat(destination.exchange()).isEqualTo(AmqpNames.EXCHANGE_RESULTS);
    assertThat(destination.routingKey()).isEqualTo("bff");
  }

  @Test
  void resolveResultAcceptsAcademicAsDestination() {
    RoutingKeyResolver.Destination destination = resolver.resolveResult("academic");
    assertThat(destination.exchange()).isEqualTo(AmqpNames.EXCHANGE_RESULTS);
    assertThat(destination.routingKey()).isEqualTo("academic");
  }

  @Test
  void resolveResultAcceptsAuthorizationAsDestination() {
    RoutingKeyResolver.Destination destination = resolver.resolveResult("authorization");
    assertThat(destination.routingKey()).isEqualTo("authorization");
  }

  @Test
  void resolveResultRejectsBlankDestination() {
    assertThatThrownBy(() -> resolver.resolveResult("   ")).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void resolveResultRejectsNullDestination() {
    assertThatThrownBy(() -> resolver.resolveResult(null)).isInstanceOf(IllegalArgumentException.class);
  }
}
