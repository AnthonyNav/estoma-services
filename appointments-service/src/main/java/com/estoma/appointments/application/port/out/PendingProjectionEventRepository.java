package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PendingProjectionEventRepository {
  void defer(PendingProjectionEvent event);
  List<PendingProjectionEvent> claim(String projectionName, int limit, String workerId,
      Instant now, Instant leaseUntil);
  boolean completeBySourceEvent(UUID sourceEventId, Instant completedAt);
  boolean fail(UUID pendingEventId, String workerId, String error, Instant availableAt);

  record PendingProjectionEvent(UUID pendingEventId, UUID sourceEventId, String projectionName,
      String messageType, Long aggregateVersion, String envelopeJson, String status, int attempts,
      Instant availableAt, String claimedBy, Instant leaseUntil) {}
}
