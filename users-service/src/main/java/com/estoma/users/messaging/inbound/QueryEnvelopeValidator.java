package com.estoma.users.messaging.inbound;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Structural validation for canonical Users Queries. */
@Component
public class QueryEnvelopeValidator {

  private static final int SUPPORTED_MESSAGE_VERSION = 1;
  private static final Set<String> AUTHORIZATION_QUERIES = Set.of(
      MessageTypes.GET_PERSON_STATUS_SNAPSHOT,
      MessageTypes.LIST_PERSON_STATUS_SNAPSHOTS_PAGE);

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
      throw new InvalidMessageException("expected messageKind=QUERY on queries.users, got " + envelope.messageKind());
    }
    if (envelope.messageVersion() != SUPPORTED_MESSAGE_VERSION) {
      throw new InvalidMessageException("unsupported messageVersion " + envelope.messageVersion());
    }
    if (!MessageTypes.QUERIES.contains(envelope.messageType())) {
      throw new InvalidMessageException("unrecognized query messageType " + envelope.messageType());
    }
    if (!properties.isQueryAllowed(envelope.producer(), envelope.messageType())) {
      throw new InvalidMessageException("producer not authorized for this query: " + envelope.producer());
    }
    if (envelope.operationId() == null) {
      throw new InvalidMessageException("queries require operationId for result correlation");
    }
    if (!(envelope.actor() instanceof Actor.System system)
        || system.service() == null
        || !system.service().equals(envelope.producer())) {
      throw new InvalidMessageException("queries require SYSTEM actor matching producer");
    }
    if (MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL.equals(envelope.messageType())
        && !"academic".equals(envelope.producer())) {
      throw new InvalidMessageException("ResolvePersonByInstitutionalEmail is restricted to academic");
    }
    if (AUTHORIZATION_QUERIES.contains(envelope.messageType())
        && !"authorization".equals(envelope.producer())) {
      throw new InvalidMessageException("person status recovery queries are restricted to authorization");
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
