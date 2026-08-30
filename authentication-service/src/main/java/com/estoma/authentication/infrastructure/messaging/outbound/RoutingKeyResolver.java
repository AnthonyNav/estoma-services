package com.estoma.authentication.infrastructure.messaging.outbound;

import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.infrastructure.messaging.AmqpNames;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Resolves the (exchange, routingKey) pair the Outbox Relay publishes a
 * claimed row to. Only RESULT and EVENT kinds are resolved here --
 * {@code authentication-service} never publishes COMMAND messages.
 *
 * <p>RESULT routing is resolved from the internal V1 requester whitelist.
 * The requester is Outbox transport metadata only; it is not serialized into
 * the common envelope and does not represent message ownership.
 *
 * <p>EVENT routing keys are a fixed, static table: exactly one key per
 * event {@code messageType}, in the {@code authentication.<subject>.
 * <eventPastTense>} shape (messaging-architecture.md §4).
 */
@Component
public class RoutingKeyResolver {

  private static final Map<String, String> EVENT_ROUTING_KEYS =
      Map.ofEntries(
          Map.entry(AuthenticationEvent.ACCOUNT_CREATED.wireName(), AmqpNames.ROUTING_KEY_ACCOUNT_CREATED),
          Map.entry(AuthenticationEvent.ACCOUNT_ACTIVATED.wireName(), AmqpNames.ROUTING_KEY_ACCOUNT_ACTIVATED),
          Map.entry(AuthenticationEvent.ACCOUNT_DEACTIVATED.wireName(), AmqpNames.ROUTING_KEY_ACCOUNT_DEACTIVATED),
          Map.entry(AuthenticationEvent.LOGIN_IDENTIFIER_CHANGED.wireName(), AmqpNames.ROUTING_KEY_LOGIN_IDENTIFIER_CHANGED),
          Map.entry(
              AuthenticationEvent.STUDENT_LOGIN_IDENTIFIER_SYNCHRONIZATION_FAILED.wireName(),
              AmqpNames.ROUTING_KEY_STUDENT_LOGIN_SYNC_FAILED),
          Map.entry(AuthenticationEvent.PASSWORD_CHANGED.wireName(), AmqpNames.ROUTING_KEY_PASSWORD_CHANGED),
          Map.entry(AuthenticationEvent.TEMPORARY_PASSWORD_SET.wireName(), AmqpNames.ROUTING_KEY_TEMPORARY_PASSWORD_SET),
          Map.entry(
              AuthenticationEvent.TEMPORARY_PASSWORD_REGENERATED.wireName(), AmqpNames.ROUTING_KEY_TEMPORARY_PASSWORD_REGENERATED),
           Map.entry(AuthenticationEvent.SESSION_REVOKED.wireName(), AmqpNames.ROUTING_KEY_SESSION_REVOKED),
           Map.entry(AuthenticationEvent.ALL_ACCOUNT_SESSIONS_REVOKED.wireName(), AmqpNames.ROUTING_KEY_ALL_ACCOUNT_SESSIONS_REVOKED),
           Map.entry(AuthenticationEvent.OTHER_ACCOUNT_SESSIONS_REVOKED.wireName(), AmqpNames.ROUTING_KEY_OTHER_ACCOUNT_SESSIONS_REVOKED),
           Map.entry(AuthenticationEvent.PASSWORD_RECOVERY_REQUESTED.wireName(), AmqpNames.ROUTING_KEY_PASSWORD_RECOVERY_REQUESTED));

  private static final Map<String, String> RESULT_ROUTING_KEYS =
      Map.of("bff", AmqpNames.ROUTING_KEY_RESULTS_BFF,
          "academic", AmqpNames.ROUTING_KEY_RESULTS_ACADEMIC);

  public record Destination(String exchange, String routingKey) {}

  public Destination resolveEvent(String messageType) {
    String routingKey = EVENT_ROUTING_KEYS.get(messageType);
    if (routingKey == null) {
      throw new IllegalArgumentException("no routing key configured for event messageType " + messageType);
    }
    return new Destination(MessagingNames.EVENTS_EXCHANGE, routingKey);
  }

  public Destination resolveResult(String requester) {
    String routingKey = RESULT_ROUTING_KEYS.get(requester);
    if (routingKey == null) {
      throw new IllegalArgumentException("no result routing key configured for requester " + requester);
    }
    return new Destination(MessagingNames.RESULTS_EXCHANGE, routingKey);
  }

  public static Map<String, String> eventRoutingKeys() {
    return EVENT_ROUTING_KEYS;
  }
}
