package com.estoma.platformbff.domain.operation;

public enum OperationStatus {
  PENDING,
  SUCCEEDED,
  REJECTED,
  FAILED,
  EXPIRED;

  public boolean terminal() {
    return this != PENDING;
  }
}
