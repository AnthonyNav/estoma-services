package com.estoma.academic.messaging;

import com.estoma.academic.contract.MessageEnvelope;
import java.util.Map;
import java.util.Set;

/** Closed allow-list for the external events that Academic is allowed to project. */
public final class ExternalEventContract {
  private static final Map<String, Set<String>> PRODUCERS = Map.ofEntries(
      Map.entry("PersonCreated", Set.of("users", "users-service")),
      Map.entry("PersonUpdated", Set.of("users", "users-service")),
      Map.entry("PersonActivated", Set.of("users", "users-service")),
      Map.entry("PersonDeactivated", Set.of("users", "users-service")),
      Map.entry("AccountCreated", Set.of("authentication")),
      Map.entry("AccountActivated", Set.of("authentication")),
      Map.entry("AccountDeactivated", Set.of("authentication")),
      Map.entry("AccountRoleAssigned", Set.of("authorization")),
      Map.entry("AccountRoleChanged", Set.of("authorization")),
      Map.entry("SystemAccessGranted", Set.of("authorization")),
      Map.entry("SystemAccessSuspended", Set.of("authorization")),
      Map.entry("SystemAccessRevoked", Set.of("authorization")),
      Map.entry("SystemAccessRestored", Set.of("authorization")));

  private ExternalEventContract() {}

  public static void validate(MessageEnvelope envelope) {
     if (envelope == null || envelope.messageId() == null || envelope.occurredAt() == null || envelope.messageVersion() != 1
        || !"EVENT".equals(envelope.messageKind()) || envelope.messageType() == null) {
      throw new IllegalArgumentException("invalid external event envelope");
    }
    Set<String> expectedProducers = PRODUCERS.get(envelope.messageType());
    if (expectedProducers == null) {
      throw new IllegalArgumentException("unsupported external event " + envelope.messageType());
    }
    if (!expectedProducers.contains(envelope.producer())) {
      throw new IllegalArgumentException("producer does not own " + envelope.messageType());
    }
  }

  public static Map<String, Set<String>> producers() {
    return PRODUCERS;
  }
}
