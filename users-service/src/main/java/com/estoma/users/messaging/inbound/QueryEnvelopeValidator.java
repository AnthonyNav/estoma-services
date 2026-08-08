package com.estoma.users.messaging.inbound;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import org.springframework.stereotype.Component;

/**
 * Structural and contractual validation of an inbound Query envelope —
 * the Query counterpart of {@link EnvelopeValidator}, kept as a sibling
 * class rather than a parameterized shared one: the actor requirement
 * genuinely differs (a Query carries a technical {@link Actor.System}
 * actor, never {@link Actor.Account}, and needs no {@code
 * idempotencyKey}), and branching one class for both would be harder to
 * read than either is today.
 *
 * <p>Anything this rejects is a permanent failure — {@link
 * InvalidMessageException} — straight to the DLQ, never a retry tier
 * (docs/system-contract.md §7, applied here by analogy).
 */
@Component
public class QueryEnvelopeValidator {

  private static final int SUPPORTED_MESSAGE_VERSION = 1;

  private final MessagingProperties properties;

  public QueryEnvelopeValidator(MessagingProperties properties) {
    this.properties = properties;
  }

  public void validate(MessageEnvelope envelope) {
    requireNonNull(envelope, "envelope");
    requireNonNull(envelope.messageId(), "messageId");
    requireNonNull(envelope.occurredAt(), "occurredAt");
    requireNonNull(envelope.correlationId(), "correlationId");
    requireNonNull(envelope.payload(), "payload");
    requireNonBlank(envelope.messageType(), "messageType");
    requireNonBlank(envelope.producer(), "producer");
    requireNonNull(envelope.messageKind(), "messageKind");

    if (envelope.messageKind() != MessageKind.QUERY) {
      throw new InvalidMessageException(
          "expected messageKind=QUERY on queries.users-service, got " + envelope.messageKind());
    }
    if (envelope.messageVersion() != SUPPORTED_MESSAGE_VERSION) {
      throw new InvalidMessageException(
          "unsupported messageVersion " + envelope.messageVersion() + " for " + envelope.messageType());
    }
    if (!MessageTypes.QUERIES.contains(envelope.messageType())) {
      throw new InvalidMessageException("unrecognized query messageType " + envelope.messageType());
    }
    if (!properties.isQueryAllowed(envelope.producer(), envelope.messageType())) {
      throw new InvalidMessageException(
          "producer not authorized for this query: producer="
              + envelope.producer()
              + ", messageType="
              + envelope.messageType());
    }
    if (envelope.operationId() == null) {
      throw new InvalidMessageException("queries require operationId for result correlation");
    }
    if (!(envelope.actor() instanceof Actor.System system)
        || system.service() == null
        || system.service().isBlank()
        || !"academic".equals(system.service())) {
      // A Query is issued by a service acting on its own behalf, never a
      // human -- an ACCOUNT actor here would be structurally wrong, not
      // just unauthorized (messaging-architecture.md §8.1).
      throw new InvalidMessageException("ResolvePersonByInstitutionalEmail requires SYSTEM actor academic");
    }
  }

  private static void requireNonNull(Object value, String fieldName) {
    if (value == null) {
      throw new InvalidMessageException(fieldName + " must not be null");
    }
  }

  private static void requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new InvalidMessageException(fieldName + " must not be blank");
    }
  }
}
