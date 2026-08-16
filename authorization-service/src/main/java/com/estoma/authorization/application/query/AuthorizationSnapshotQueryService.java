package com.estoma.authorization.application.query;

import com.estoma.authorization.application.port.AuthorizationSnapshotRepository;
import com.estoma.authorization.application.port.AuthorizationSnapshotRepository.AccountAuthorizationSnapshot;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Broker-agnostic use cases for the two authoritative Authorization recovery Queries. */
public final class AuthorizationSnapshotQueryService {

  private static final Set<String> ALLOWED_REQUESTERS = Set.of(
      "bff", "appointments", "wash-administration", "wash-execution", "academic");
  private static final Set<String> LAVADO_ONLY = Set.of(
      "appointments", "wash-administration", "wash-execution");

  private final AuthorizationSnapshotRepository snapshots;
  private final Clock clock;

  public AuthorizationSnapshotQueryService(
      AuthorizationSnapshotRepository snapshots, Clock clock) {
    this.snapshots = snapshots;
    this.clock = clock;
  }

  public QueryResult get(
      String requester, UUID accountId, SystemCode systemCode) {
    validateRequester(requester, systemCode);
    Objects.requireNonNull(accountId, "accountId");
    Instant now = clock.instant();
    return snapshots.find(accountId, systemCode)
        .<QueryResult>map(snapshot -> new QueryResult(
            Status.FOUND, List.of(snapshot), null, false, now, null))
        .orElseGet(() -> new QueryResult(
            Status.NOT_FOUND, List.of(), null, false, now, null));
  }

  public QueryResult list(
      String requester, String cursor, int limit, SystemCode systemCode) {
    validateRequester(requester, systemCode);
    if (limit <= 0 || limit > 1000) {
      return rejected("INVALID_LIMIT");
    }
    try {
      var page = snapshots.list(cursor, limit, systemCode);
      return new QueryResult(
          Status.SUCCEEDED,
          page.items(),
          page.nextCursor(),
          page.hasMore(),
          clock.instant(),
          null);
    } catch (IllegalArgumentException invalidCursor) {
      return rejected("INVALID_CURSOR");
    }
  }

  private void validateRequester(String requester, SystemCode systemCode) {
    if (!ALLOWED_REQUESTERS.contains(requester)) {
      throw new QueryRejectedException("QUERY_PRODUCER_NOT_ALLOWED");
    }
    if (LAVADO_ONLY.contains(requester)
        && systemCode != SystemCode.LAVADO_ULTRASONICO) {
      throw new QueryRejectedException("QUERY_SCOPE_NOT_ALLOWED");
    }
  }

  private QueryResult rejected(String errorCode) {
    return new QueryResult(Status.REJECTED, List.of(), null, false, clock.instant(), errorCode);
  }

  public enum Status {
    FOUND,
    NOT_FOUND,
    SUCCEEDED,
    REJECTED
  }

  public record QueryResult(
      Status status,
      List<AccountAuthorizationSnapshot> items,
      String nextCursor,
      boolean hasMore,
      Instant snapshotGeneratedAt,
      String errorCode) {
    public QueryResult {
      items = List.copyOf(items == null ? List.of() : items);
    }
  }

  public static final class QueryRejectedException extends RuntimeException {
    private final String code;

    public QueryRejectedException(String code) {
      super(code);
      this.code = code;
    }

    public String code() {
      return code;
    }
  }
}
