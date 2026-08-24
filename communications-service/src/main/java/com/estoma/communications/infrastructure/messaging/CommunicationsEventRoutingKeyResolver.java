package com.estoma.communications.infrastructure.messaging;

import java.util.Map;
import org.springframework.stereotype.Component;

/** Resolves Communications integration Events to the canonical platform.events routing-key namespace. */
@Component
public class CommunicationsEventRoutingKeyResolver {

  private static final Map<String, String> ROUTING_KEYS = Map.ofEntries(
      Map.entry("CommunicationRequested", "communications.communication.requested"),
      Map.entry("InboxMessageCreated", "communications.inbox-message.created"),
      Map.entry("InboxVisited", "communications.inbox.visited"),
      Map.entry("InboxMessageExpired", "communications.inbox-message.expired"),
      Map.entry("EmailDeliveryAccepted", "communications.email-delivery.accepted"),
      Map.entry("EmailDeliveryRetryScheduled", "communications.email-delivery.retry-scheduled"),
      Map.entry("EmailProviderFallbackActivated", "communications.email-provider.fallback-activated"),
      Map.entry("EmailDeliveryFailed", "communications.email-delivery.failed"),
      Map.entry("EmailDeliveryDelivered", "communications.email-delivery.delivered"),
      Map.entry("CommunicationCompleted", "communications.communication.completed"),
      Map.entry("CommunicationFailed", "communications.communication.failed"));

  public String resolve(String messageType) {
    String routingKey = ROUTING_KEYS.get(messageType);
    if (routingKey == null) {
      throw new IllegalArgumentException("unsupported Communications event messageType " + messageType);
    }
    return routingKey;
  }
}
