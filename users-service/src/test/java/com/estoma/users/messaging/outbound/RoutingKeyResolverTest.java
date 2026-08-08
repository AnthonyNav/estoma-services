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
    // The guard against adding an event to MessageTypes and forgetting to
    // give it a routing key here.
    assertThat(RoutingKeyResolver.eventRoutingKeys().keySet())
        .containsExactlyInAnyOrderElementsOf(MessageTypes.EVENTS);
  }

  @ParameterizedTest
  @CsvSource({
    "PersonCreated, identity.person.created",
    "PersonUpdated, identity.person.updated",
    "PersonActivated, identity.person.activated",
    "PersonDeactivated, identity.person.deactivated",
  })
  void resolveEventReturnsTheEventsExchangeAndTheFixedRoutingKey(String messageType, String expectedKey) {
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
  void resolveResultAcceptsADifferentAuthorizedCallerAsDestination() {
    RoutingKeyResolver.Destination destination = resolver.resolveResult("student-import-process");

    assertThat(destination.routingKey()).isEqualTo("student-import-process");
  }

  @Test
  void resolveResultAcceptsAcademicAsDestination() {
    // No resolver code change needed for the Query's Result: it's routed
    // exactly like a Command's (messaging-architecture.md §4) -- this
    // documents that the new caller needs nothing new here.
    RoutingKeyResolver.Destination destination = resolver.resolveResult("academic");

    assertThat(destination.exchange()).isEqualTo(AmqpNames.EXCHANGE_RESULTS);
    assertThat(destination.routingKey()).isEqualTo("academic");
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
