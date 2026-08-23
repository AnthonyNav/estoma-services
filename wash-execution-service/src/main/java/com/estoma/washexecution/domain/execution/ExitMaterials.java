package com.estoma.washexecution.domain.execution;

import com.estoma.washexecution.domain.shared.DomainRuleViolationException;

public record ExitMaterials(
    int packageCount,
    int greenPaperCassette8Count,
    int greenPaperCassette10Count,
    int witnessTapePortionCount) {

  public ExitMaterials {
    if (packageCount < 0 || greenPaperCassette8Count < 0 || greenPaperCassette10Count < 0
        || witnessTapePortionCount < 0) {
      throw new DomainRuleViolationException("INVALID_EXIT_VALUES");
    }
    if (packageCount == 0 && greenPaperCassette8Count == 0
        && greenPaperCassette10Count == 0 && witnessTapePortionCount == 0) {
      throw new DomainRuleViolationException("EMPTY_EXIT");
    }
  }
}
