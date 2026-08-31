package com.estoma.authorization.application.query;

import com.estoma.authorization.application.port.SystemCatalogRepository;
import com.estoma.authorization.domain.model.CatalogStatus;
import com.estoma.authorization.domain.model.SystemCatalog;
import com.estoma.authorization.domain.model.SystemCode;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Authoritative, recoverable snapshots of Authorization's versioned System catalog. */
public final class SystemCatalogSnapshotQueryService {

  private static final Set<String> ALLOWED_REQUESTERS = Set.of("bff");

  private final SystemCatalogRepository systems;
  private final Clock clock;

  public SystemCatalogSnapshotQueryService(SystemCatalogRepository systems, Clock clock) {
    this.systems = systems;
    this.clock = clock;
  }

  public QueryResult get(String requester, SystemCode systemCode) {
    validateRequester(requester);
    Objects.requireNonNull(systemCode, "systemCode");
    Instant now = clock.instant();
    return systems.findByCode(systemCode.name())
        .<QueryResult>map(system -> new QueryResult(
            Status.FOUND, List.of(snapshot(system)), null, false, now, null))
        .orElseGet(() -> new QueryResult(Status.NOT_FOUND, List.of(), null, false, now, null));
  }

  public QueryResult list(String requester, String cursor, int limit) {
    validateRequester(requester);
    if (limit <= 0 || limit > 1000) {
      return rejected("INVALID_LIMIT");
    }
    try {
      String normalizedCursor = cursor == null || cursor.isBlank() ? null : SystemCode.parse(cursor).name();
      List<SystemSnapshot> items = systems.findAll().stream()
          .sorted(Comparator.comparing(system -> system.code().name()))
          .filter(system -> normalizedCursor == null || system.code().name().compareTo(normalizedCursor) > 0)
          .map(SystemCatalogSnapshotQueryService::snapshot)
          .limit(Math.addExact(limit, 1))
          .toList();
      boolean hasMore = items.size() > limit;
      List<SystemSnapshot> page = hasMore ? items.subList(0, limit) : items;
      String nextCursor = hasMore ? page.getLast().systemCode().name() : null;
      return new QueryResult(Status.SUCCEEDED, page, nextCursor, hasMore, clock.instant(), null);
    } catch (IllegalArgumentException invalidCursor) {
      return rejected("INVALID_CURSOR");
    }
  }

  private static void validateRequester(String requester) {
    if (!ALLOWED_REQUESTERS.contains(requester)) {
      throw new AuthorizationSnapshotQueryService.QueryRejectedException(
          "QUERY_PRODUCER_NOT_ALLOWED");
    }
  }

  private QueryResult rejected(String errorCode) {
    return new QueryResult(Status.REJECTED, List.of(), null, false, clock.instant(), errorCode);
  }

  private static SystemSnapshot snapshot(SystemCatalog system) {
    return new SystemSnapshot(system.id(), system.code(), system.status(), system.version());
  }

  public enum Status {
    FOUND,
    NOT_FOUND,
    SUCCEEDED,
    REJECTED
  }

  public record SystemSnapshot(
      java.util.UUID systemId,
      SystemCode systemCode,
      CatalogStatus status,
      int sourceVersion) {
    public SystemSnapshot {
      Objects.requireNonNull(systemId, "systemId");
      Objects.requireNonNull(systemCode, "systemCode");
      Objects.requireNonNull(status, "status");
      if (sourceVersion <= 0) {
        throw new IllegalArgumentException("sourceVersion must be positive");
      }
    }
  }

  public record QueryResult(
      Status status,
      List<SystemSnapshot> items,
      String nextCursor,
      boolean hasMore,
      Instant snapshotGeneratedAt,
      String errorCode) {
    public QueryResult {
      items = List.copyOf(items == null ? List.of() : items);
    }
  }
}
