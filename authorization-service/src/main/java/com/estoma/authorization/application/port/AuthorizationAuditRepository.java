package com.estoma.authorization.application.port;

import com.estoma.authorization.domain.model.ActorRef;
import java.time.Instant;
import java.util.UUID;

public interface AuthorizationAuditRepository {

  void append(AuditEntry entry);

  record AuditEntry(
      UUID auditId,
      UUID sourceMessageId,
      String sourceProducer,
      String aggregateType,
      UUID aggregateId,
      Integer aggregateVersion,
      String action,
      ActorRef actor,
      UUID businessInitiatorAccountId,
      UUID businessImportId,
      UUID correlationId,
      UUID causationId,
      UUID operationId,
      String idempotencyKey,
      String result,
      String reasonCode,
      String reasonDetail,
      Instant occurredAt) {}
}
