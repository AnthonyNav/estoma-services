package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.query.AuthorizationQueryMetadata;
import com.estoma.authorization.application.query.AuthorizationSnapshotQuery;
import com.estoma.authorization.application.query.AuthorizationSnapshotQuery.GetAccountAuthorizationSnapshot;
import com.estoma.authorization.application.query.AuthorizationSnapshotQuery.GetSystemCatalogSnapshot;
import com.estoma.authorization.application.query.AuthorizationSnapshotQuery.ListAccountAuthorizationSnapshotsPage;
import com.estoma.authorization.application.query.AuthorizationSnapshotQuery.ListSystemCatalogSnapshotsPage;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.domain.model.SystemCode;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
final class QueryMessageMapper {

  MappedQuery map(MessageEnvelope envelope) {
    JsonNode payload = envelope.payload();
    AuthorizationSnapshotQuery query = switch (envelope.messageType()) {
      case "GetAccountAuthorizationSnapshot" -> point(payload);
      case "ListAccountAuthorizationSnapshotsPage" -> page(payload);
      case "GetSystemCatalogSnapshot" -> systemCatalogPoint(payload);
      case "ListSystemCatalogSnapshotsPage" -> systemCatalogPage(payload);
      default -> throw new InvalidMessageException("unsupported Authorization Query");
    };
    return new MappedQuery(
        new AuthorizationQueryMetadata(
            envelope.messageId(),
            envelope.messageType(),
            envelope.producer(),
            envelope.occurredAt(),
            envelope.correlationId(),
            envelope.causationId(),
            envelope.operationId(),
            WireValues.actor(envelope.actor())),
        query);
  }

  private static AuthorizationSnapshotQuery point(JsonNode payload) {
    Set<String> fields = Set.of("accountId", "systemCode");
    WireValues.fields(payload, Set.of("accountId"), fields);
    return new GetAccountAuthorizationSnapshot(
        WireValues.uuid(payload, "accountId"), optionalSystemCode(payload));
  }

  private static AuthorizationSnapshotQuery page(JsonNode payload) {
    Set<String> fields = Set.of("cursor", "limit", "systemCode");
    WireValues.fields(payload, Set.of("limit"), fields);
    int limit = WireValues.positiveInt(payload, "limit");
    if (limit > 1000) {
      throw new InvalidMessageException("limit must be at most 1000");
    }
    String cursor = payload.hasNonNull("cursor") ? WireValues.uuid(payload, "cursor").toString() : null;
    return new ListAccountAuthorizationSnapshotsPage(
        cursor, limit, optionalSystemCode(payload));
  }

  private static AuthorizationSnapshotQuery systemCatalogPoint(JsonNode payload) {
    WireValues.fields(payload, Set.of("systemCode"), Set.of("systemCode"));
    return new GetSystemCatalogSnapshot(WireValues.enumValue(payload, "systemCode", SystemCode.class));
  }

  private static AuthorizationSnapshotQuery systemCatalogPage(JsonNode payload) {
    WireValues.fields(payload, Set.of("limit"), Set.of("cursor", "limit"));
    int limit = WireValues.positiveInt(payload, "limit");
    if (limit > 1000) {
      throw new InvalidMessageException("limit must be at most 1000");
    }
    String cursor = payload.hasNonNull("cursor")
        ? WireValues.enumValue(payload, "cursor", SystemCode.class).name()
        : null;
    return new ListSystemCatalogSnapshotsPage(cursor, limit);
  }

  private static SystemCode optionalSystemCode(JsonNode payload) {
    return payload.hasNonNull("systemCode")
        ? WireValues.enumValue(payload, "systemCode", SystemCode.class)
        : null;
  }

  record MappedQuery(AuthorizationQueryMetadata metadata, AuthorizationSnapshotQuery query) {}
}
