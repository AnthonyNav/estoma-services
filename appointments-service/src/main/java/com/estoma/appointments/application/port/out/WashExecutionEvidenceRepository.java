package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface WashExecutionEvidenceRepository {
  EvidenceRecord recordArrival(EvidenceRecord evidence);
  EvidenceRecord recordEntryAuthorization(EvidenceRecord evidence);
  Optional<EvidenceRecord> findByAppointmentId(UUID appointmentId);
  Optional<EvidenceRecord> findByExecutionId(UUID executionId);
  boolean hasValidArrival(UUID appointmentId, Instant arrivalToleranceUntil);
  record EvidenceRecord(UUID evidenceId, UUID washExecutionId, UUID appointmentId,
      Instant arrivedAt, Long arrivalExecutionVersion, Instant entryAuthorizedAt,
      Long entryAuthorizedExecutionVersion, UUID sourceEventId, String sourceEventType,
      Instant updatedAt) {
    public EvidenceRecord {
      if (evidenceId == null || washExecutionId == null || appointmentId == null || sourceEventId == null
          || sourceEventType == null || updatedAt == null) throw new IllegalArgumentException("invalid evidence");
    }
  }
}
