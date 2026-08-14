package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.UUID;

/** Stores the non-personal audit data required by administrative commands. */
public interface AdministrativeAuditRepository {

  void append(AuditRecord record);

  record AuditRecord(
      UUID auditId,
      UUID operationId,
      UUID correlationId,
      String operationType,
      UUID aggregateId,
      UUID accountId,
      String reason,
      Instant recordedAt) {}
}
