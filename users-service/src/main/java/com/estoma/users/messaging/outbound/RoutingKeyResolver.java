package com.estoma.users.messaging.outbound;

import com.estoma.users.contract.MessageTypes;
import com.estoma.users.messaging.AmqpNames;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves the (exchange, routingKey) pair the outbox relay publishes a
 * claimed row to. Only RESULT and EVENT kinds are resolved here —
 * {@code users-service} never publishes COMMAND messages
 * (docs/system-contract.md §9).
 *
 * <p>EVENT routing keys are a fixed, static table: exactly one key per
 * event {@code messageType}, in the {@code <context>.<aggregate>.
 * <eventPastTense>} shape. {@link #eventRoutingKeys()} exposes the table
 * so a test can assert it covers every constant in {@link MessageTypes}
 * — the guard against adding an event and forgetting its key.
 *
 * <p>RESULT routing keys are deliberately NOT resolved from {@code
 * messageType}: the same result type (e.g. {@code CreatePersonSucceeded})
 * can go to different destinations depending on who issued the command —
 * {@code bff} today, a future authorized administrative import process
 * tomorrow (commands.md:254). That destination travels with the outbox
 * row's {@code metadata} (captured from the command's {@code producer} at
 * handling time) and is passed in directly by the caller.
 */
@Component
public class RoutingKeyResolver {

  private static final Map<String, String> EVENT_ROUTING_KEYS =
      Map.of(
          MessageTypes.PERSON_CREATED, AmqpNames.ROUTING_KEY_PERSON_CREATED,
          MessageTypes.PERSON_UPDATED, AmqpNames.ROUTING_KEY_PERSON_UPDATED,
          MessageTypes.PERSON_ACTIVATED, AmqpNames.ROUTING_KEY_PERSON_ACTIVATED,
          MessageTypes.PERSON_DEACTIVATED, AmqpNames.ROUTING_KEY_PERSON_DEACTIVATED);

  public record Destination(String exchange, String routingKey) {}

  public Destination resolveEvent(String messageType) {
    String routingKey = EVENT_ROUTING_KEYS.get(messageType);
    if (routingKey == null) {
      throw new IllegalArgumentException(
          "no routing key configured for event messageType " + messageType);
    }
    return new Destination(AmqpNames.EXCHANGE_EVENTS, routingKey);
  }

  public Destination resolveResult(String destinationComponent) {
    if (destinationComponent == null || destinationComponent.isBlank()) {
      throw new IllegalArgumentException("a RESULT message requires a destination component");
    }
    return new Destination(AmqpNames.EXCHANGE_RESULTS, destinationComponent);
  }

  public static Map<String, String> eventRoutingKeys() {
    return EVENT_ROUTING_KEYS;
  }
}
