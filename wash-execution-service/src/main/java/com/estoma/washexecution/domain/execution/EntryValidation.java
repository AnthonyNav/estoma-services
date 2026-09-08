package com.estoma.washexecution.domain.execution;

import com.estoma.washexecution.domain.shared.DomainRuleViolationException;
import java.time.Instant;
import java.util.UUID;

public record EntryValidation(
    UUID entryValidationId,
    UUID washExecutionId,
    Boolean identityConfirmed,
    Boolean requirementsSatisfied,
    EntryDecision decision,
    String rejectionReason,
    UUID decidedByAccountId,
    Instant decidedAt) {

  public EntryValidation {
    if (entryValidationId == null || washExecutionId == null || decision == null
        || decidedByAccountId == null || decidedAt == null) {
      throw new DomainRuleViolationException("INVALID_ENTRY_VALIDATION");
    }
    if (decision == EntryDecision.AUTHORIZED) {
      if (!Boolean.TRUE.equals(identityConfirmed) || !Boolean.TRUE.equals(requirementsSatisfied)
          || rejectionReason != null) {
        throw new DomainRuleViolationException("INVALID_AUTHORIZED_ENTRY");
      }
    } else {
      if (rejectionReason == null || rejectionReason.isBlank()) {
        throw new DomainRuleViolationException("REJECTION_REASON_REQUIRED");
      }
      if ((identityConfirmed == null) != (requirementsSatisfied == null)) {
        throw new DomainRuleViolationException("INVALID_ENTRY_VALIDATION");
      }
    }
  }
}
