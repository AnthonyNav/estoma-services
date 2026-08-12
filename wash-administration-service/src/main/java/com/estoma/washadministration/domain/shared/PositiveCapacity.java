package com.estoma.washadministration.domain.shared;

public record PositiveCapacity(int value) {

  public PositiveCapacity {
    if (value <= 0) {
      throw new DomainRuleViolationException(DomainErrorCode.TANK_INVALID_CAPACITY, "capacity must be positive");
    }
  }
}
