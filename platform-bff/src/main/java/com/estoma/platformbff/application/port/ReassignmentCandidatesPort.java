package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable, owner-derived reassignment-candidate snapshots and their asynchronous refreshes. */
public interface ReassignmentCandidatesPort {
  Optional<ProjectedExecution> findProjectedExecution(UUID washExecutionId);

  Optional<Snapshot> findSnapshot(UUID washExecutionId);

  int enqueueDuePending(Instant now, Instant refreshBefore, Instant abandonBefore, int limit);

  Optional<Refresh> findRefreshByMessageId(UUID messageId);

  Optional<Refresh> claimNext(Instant now);

  boolean markPublished(UUID messageId, UUID claimToken, Instant publishedAt);

  boolean markFailed(UUID messageId, UUID claimToken, String errorCode, Instant nextAttemptAt);

  boolean applySnapshot(Refresh expectedRefresh, UUID resultMessageId, Snapshot snapshot,
      Instant appliedAt);

  boolean rejectRefresh(Refresh expectedRefresh, UUID resultMessageId, String errorCode,
      Instant appliedAt);

  int cleanupInactive(Instant olderThan, int limit);

  record ProjectedExecution(UUID washExecutionId, String status, long executionVersion) {}

  record Refresh(
      UUID washExecutionId,
      long executionVersion,
      UUID messageId,
      UUID correlationId,
      String status,
      UUID claimToken,
      Instant claimUntil,
      int attemptCount,
      Instant nextAttemptAt,
      Instant publishedAt,
      String lastErrorCode,
      Instant requestedAt) {
    public Refresh {
      if (washExecutionId == null || executionVersion < 1 || messageId == null
          || correlationId == null || status == null || status.isBlank() || attemptCount < 0
          || requestedAt == null) {
        throw new IllegalArgumentException("invalid reassignment candidates refresh");
      }
    }
  }

  record Snapshot(
      UUID washExecutionId,
      long executionVersion,
      UUID resultMessageId,
      Instant snapshotGeneratedAt,
      Instant freshUntil,
      List<Candidate> candidates) {
    public Snapshot {
      candidates = List.copyOf(candidates);
      if (washExecutionId == null || executionVersion < 1 || resultMessageId == null
          || snapshotGeneratedAt == null || freshUntil == null
          || freshUntil.isBefore(snapshotGeneratedAt)) {
        throw new IllegalArgumentException("invalid reassignment candidates snapshot");
      }
    }
  }

  record Candidate(
      UUID cabinId,
      String cabinCode,
      String cabinName,
      UUID tankId,
      String tankCode,
      String tankName,
      int availableCapacity) {
    public Candidate {
      if (cabinId == null || blank(cabinCode) || blank(cabinName) || tankId == null
          || blank(tankCode) || blank(tankName) || availableCapacity <= 0) {
        throw new IllegalArgumentException("invalid reassignment candidate");
      }
    }

    private static boolean blank(String value) {
      return value == null || value.isBlank();
    }
  }
}
