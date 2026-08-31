package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.AccountStatusSnapshotQueryService;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Strict V1 validation for requester-scoped Authentication recovery Queries. */
@Component
public class QueryEnvelopeValidator {

  private static final Set<String> AUTHORIZATION_TYPES = Set.of(
      AccountStatusSnapshotQueryService.GET,
      AccountStatusSnapshotQueryService.LIST);
  private static final Set<String> BFF_TYPES = Set.of(
      AccountStatusSnapshotQueryService.GET_PRESENTATION,
      AccountStatusSnapshotQueryService.LIST_PRESENTATION);

  public void validate(MessageEnvelope envelope) {
    if (envelope == null
        || envelope.messageId() == null
        || envelope.messageVersion() != 1
        || envelope.messageKind() != MessageKind.QUERY
        || (!AUTHORIZATION_TYPES.contains(envelope.messageType()) && !BFF_TYPES.contains(envelope.messageType()))
        || envelope.occurredAt() == null
        || envelope.correlationId() == null
        || envelope.operationId() == null
        || envelope.idempotencyKey() != null
        || envelope.payload() == null
        || !envelope.payload().isObject()
        || envelope.actor() == null
        || envelope.actor().accountId() != null) {
      throw new InvalidMessageException("invalid Authentication recovery Query envelope");
    }
    String requester = AUTHORIZATION_TYPES.contains(envelope.messageType()) ? "authorization" : "bff";
    if (!requester.equals(envelope.producer()) || !envelope.actor().isSystemService(requester)) {
      throw new InvalidMessageException("Query requester is not authorized for this snapshot family");
    }
    if (AccountStatusSnapshotQueryService.GET.equals(envelope.messageType())
        || AccountStatusSnapshotQueryService.GET_PRESENTATION.equals(envelope.messageType())) {
      if (!envelope.payload().hasNonNull("accountId")) {
        throw new InvalidMessageException(envelope.messageType() + " requires accountId");
      }
    } else {
      if (envelope.payload().path("limit").asInt(-1) != 200) {
        throw new InvalidMessageException(envelope.messageType() + " requires limit=200");
      }
    }
  }
}
