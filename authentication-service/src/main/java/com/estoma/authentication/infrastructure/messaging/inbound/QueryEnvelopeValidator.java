package com.estoma.authentication.infrastructure.messaging.inbound;

import com.estoma.authentication.application.AccountStatusSnapshotQueryService;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Strict V1 validation for Authorization-owned recovery Queries. */
@Component
public class QueryEnvelopeValidator {

  private static final Set<String> TYPES = Set.of(
      AccountStatusSnapshotQueryService.GET,
      AccountStatusSnapshotQueryService.LIST);

  public void validate(MessageEnvelope envelope) {
    if (envelope == null
        || envelope.messageId() == null
        || envelope.messageVersion() != 1
        || envelope.messageKind() != MessageKind.QUERY
        || !TYPES.contains(envelope.messageType())
        || !"authorization".equals(envelope.producer())
        || envelope.occurredAt() == null
        || envelope.correlationId() == null
        || envelope.operationId() == null
        || envelope.idempotencyKey() != null
        || envelope.payload() == null
        || !envelope.payload().isObject()
        || envelope.actor() == null
        || !envelope.actor().isSystemService("authorization")
        || envelope.actor().accountId() != null) {
      throw new InvalidMessageException("invalid Authorization recovery Query envelope");
    }
    if (AccountStatusSnapshotQueryService.GET.equals(envelope.messageType())) {
      if (!envelope.payload().hasNonNull("accountId")) {
        throw new InvalidMessageException("GetAccountStatusSnapshot requires accountId");
      }
    } else {
      if (envelope.payload().path("limit").asInt(-1) != 200) {
        throw new InvalidMessageException("ListAccountStatusSnapshotsPage requires limit=200");
      }
    }
  }
}
