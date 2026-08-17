package com.estoma.authorization.application.query;

import com.estoma.authorization.application.port.AuthorizationSnapshotRepository.AccessSnapshot;
import com.estoma.authorization.application.port.AuthorizationSnapshotRepository.AccountAuthorizationSnapshot;
import com.estoma.authorization.application.port.AuthorizationSnapshotRepository.RoleSnapshot;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.query.AuthorizationSnapshotQuery.GetAccountAuthorizationSnapshot;
import com.estoma.authorization.application.query.AuthorizationSnapshotQuery.ListAccountAuthorizationSnapshotsPage;
import com.estoma.authorization.application.query.AuthorizationSnapshotQueryService.QueryRejectedException;
import com.estoma.authorization.application.query.AuthorizationSnapshotQueryService.QueryResult;
import com.estoma.authorization.application.support.CanonicalJson;
import com.estoma.authorization.application.support.OutboxIntentWriter;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Writes a Query Result Outbox obligation before the AMQP adapter acknowledges the Query. */
public final class AuthorizationSnapshotQueryResponder {

  private final TransactionRunner transactions;
  private final AuthorizationSnapshotQueryService queries;
  private final OutboxRepository outbox;
  private final Clock clock;

  public AuthorizationSnapshotQueryResponder(
      TransactionRunner transactions,
      AuthorizationSnapshotQueryService queries,
      OutboxRepository outbox,
      Clock clock) {
    this.transactions = transactions;
    this.queries = queries;
    this.outbox = outbox;
    this.clock = clock;
  }

  public OutboundQueryResultIntent respond(
      AuthorizationQueryMetadata metadata, AuthorizationSnapshotQuery query) {
    return transactions.inTransaction(() -> respondInTransaction(metadata, query));
  }

  private OutboundQueryResultIntent respondInTransaction(
      AuthorizationQueryMetadata metadata, AuthorizationSnapshotQuery query) {
    QueryResult result;
    try {
      result = switch (query) {
        case GetAccountAuthorizationSnapshot point ->
            queries.get(metadata.producer(), point.accountId(), point.systemCode());
        case ListAccountAuthorizationSnapshotsPage page ->
            queries.list(metadata.producer(), page.cursor(), page.limit(), page.systemCode());
      };
    } catch (QueryRejectedException rejection) {
      result = new QueryResult(
          AuthorizationSnapshotQueryService.Status.REJECTED,
          List.of(),
          null,
          false,
          clock.instant(),
          rejection.code());
    }

    Instant now = clock.instant();
    OutboundQueryResultIntent intent = new OutboundQueryResultIntent(
        UUID.randomUUID(),
        metadata.messageType() + "Result",
        "authorization",
        metadata.producer(),
        metadata.operationId(),
        metadata.actor(),
        metadata.correlationId(),
        metadata.messageId(),
        payload(result, query),
        now);
    OutboxIntentWriter.append(outbox, intent);
    return intent;
  }

  private static String payload(QueryResult result, AuthorizationSnapshotQuery query) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", result.status().name());
    if (result.status() == AuthorizationSnapshotQueryService.Status.FOUND) {
      payload.put("data", snapshot(result.items().getFirst(), result.snapshotGeneratedAt()));
    } else if (result.status() == AuthorizationSnapshotQueryService.Status.SUCCEEDED) {
      Map<String, Object> data = new LinkedHashMap<>();
      List<Map<String, Object>> items = new ArrayList<>();
      for (AccountAuthorizationSnapshot item : result.items()) {
        items.add(snapshot(item, null));
      }
      data.put("items", items);
      if (result.nextCursor() != null) {
        data.put("nextCursor", result.nextCursor());
      }
      data.put("hasMore", result.hasMore());
      data.put("snapshotGeneratedAt", result.snapshotGeneratedAt());
      payload.put("data", data);
    } else if (result.status() == AuthorizationSnapshotQueryService.Status.REJECTED) {
      payload.put("error", Map.of("code", result.errorCode()));
    }
    return CanonicalJson.write(payload);
  }

  private static Map<String, Object> snapshot(
      AccountAuthorizationSnapshot value, Instant snapshotGeneratedAt) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("accountId", value.accountId());
    if (value.role() != null) {
      data.put("role", role(value.role()));
    }
    List<Map<String, Object>> accesses = new ArrayList<>();
    for (AccessSnapshot access : value.systemAccesses()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("systemAccessId", access.systemAccessId());
      item.put("systemId", access.systemId());
      item.put("systemCode", access.systemCode());
      item.put("status", access.status());
      item.put("accessVersion", access.accessVersion());
      accesses.add(item);
    }
    data.put("systemAccesses", accesses);
    if (snapshotGeneratedAt != null) {
      data.put("snapshotGeneratedAt", snapshotGeneratedAt);
    }
    return data;
  }

  private static Map<String, Object> role(RoleSnapshot value) {
    return Map.of("roleCode", value.roleCode(), "roleVersion", value.roleVersion());
  }
}
