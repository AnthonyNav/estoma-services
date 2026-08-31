package com.estoma.authentication.application;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.OutboxAppendRequest;
import com.estoma.authentication.application.port.OutboxPort;
import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Authoritative Account snapshots used only by requester-scoped projection recovery. */
@Component
public class AccountStatusSnapshotQueryService {

  public static final String GET = "GetAccountStatusSnapshot";
  public static final String LIST = "ListAccountStatusSnapshotsPage";
  public static final String GET_PRESENTATION = "GetAccountPresentationSnapshot";
  public static final String LIST_PRESENTATION = "ListAccountPresentationSnapshotsPage";
  private static final int PAGE_LIMIT = 200;
  private static final Actor SYSTEM = new Actor("SYSTEM", null, "authentication", null, null);

  private final AccountRepository accounts;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;

  public AccountStatusSnapshotQueryService(
      AccountRepository accounts, OutboxPort outbox, EnvelopeJsonCodec codec) {
    this.accounts = accounts;
    this.outbox = outbox;
    this.codec = codec;
  }

  @Transactional
  public void respond(MessageEnvelope query) {
    switch (query.messageType()) {
      case GET -> point(query);
      case LIST -> page(query);
      case GET_PRESENTATION -> point(query);
      case LIST_PRESENTATION -> page(query);
      default -> throw new IllegalArgumentException("unsupported Authentication Query " + query.messageType());
    }
  }

  /** Persists the terminal technical result for a structurally valid Query. */
  @Transactional
  public void fail(MessageEnvelope query, String errorCode) {
    append(query, query.messageType() + "Result", null,
        Map.of("status", "FAILED", "error", Map.of("code", errorCode)));
  }

  private void point(MessageEnvelope query) {
    UUID accountId = requiredUuid(query.payload().path("accountId").asString(null), "accountId");
    Map<String, Object> payload = new LinkedHashMap<>();
    var account = accounts.findById(accountId);
    if (account.isEmpty()) {
      payload.put("status", "NOT_FOUND");
    } else {
      payload.put("status", "FOUND");
      payload.put("data", snapshot(account.get(), isPresentation(query)));
    }
    append(query, query.messageType() + "Result", accountId, payload);
  }

  private void page(MessageEnvelope query) {
    int limit = query.payload().path("limit").asInt(-1);
    if (limit != PAGE_LIMIT) {
      throw new IllegalArgumentException(query.messageType() + " requires limit=200");
    }
    UUID cursor = query.payload().hasNonNull("cursor")
        ? requiredUuid(query.payload().path("cursor").asString(null), "cursor")
        : null;
    List<Account> rows = accounts.listAfter(cursor, PAGE_LIMIT + 1);
    boolean hasMore = rows.size() > PAGE_LIMIT;
    List<Account> visible = hasMore ? rows.subList(0, PAGE_LIMIT) : rows;
    List<Map<String, Object>> items = new ArrayList<>(visible.size());
    visible.forEach(account -> items.add(snapshot(account, isPresentation(query))));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("hasMore", hasMore);
    if (hasMore && !visible.isEmpty()) {
      data.put("nextCursor", visible.getLast().accountId());
    }
    append(query, query.messageType() + "Result", null, Map.of("status", "SUCCEEDED", "data", data));
  }

  private Map<String, Object> snapshot(Account account, boolean presentation) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("accountId", account.accountId());
    data.put("personId", account.personId());
    data.put("loginType", account.loginIdentifier().type().name());
    if (presentation) {
      data.put("loginIdentifier", account.loginIdentifier().value());
    }
    data.put(presentation ? "accountStatus" : "status", account.status().name());
    data.put("sourceVersion", account.version());
    data.put("updatedAt", account.updatedAt());
    return data;
  }

  private void append(MessageEnvelope query, String resultType, UUID aggregateId, Object payload) {
    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), resultType, 1, MessageKind.RESULT.name(), aggregateId, null,
        query.correlationId(), query.messageId(), query.operationId(), null, SYSTEM,
        query.producer(), codec.writeValueAsString(payload), Instant.now()));
  }

  private static boolean isPresentation(MessageEnvelope query) {
    return GET_PRESENTATION.equals(query.messageType()) || LIST_PRESENTATION.equals(query.messageType());
  }

  private static UUID requiredUuid(String value, String field) {
    if (value == null) {
      throw new IllegalArgumentException(field + " is required");
    }
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(field + " must be a UUID", exception);
    }
  }
}
