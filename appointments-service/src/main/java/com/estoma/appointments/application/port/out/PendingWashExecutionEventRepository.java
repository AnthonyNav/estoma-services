package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface PendingWashExecutionEventRepository {
  void defer(PendingEvent event);
  List<PendingEvent> claim(int limit, String workerId, Instant now, Instant leaseUntil);
  boolean complete(UUID pendingEventId, String workerId, Instant completedAt);
  boolean discardBySourceEventId(UUID sourceEventId, Instant completedAt);
  boolean hasOpenForAppointment(UUID appointmentId);

  record PendingEvent(UUID pendingEventId, UUID sourceEventId, UUID appointmentId,
      UUID washExecutionId, String eventType, long executionVersion, String payloadJson,
      String status, int attempts, Instant availableAt, String claimedBy, Instant leaseUntil) {}
}
