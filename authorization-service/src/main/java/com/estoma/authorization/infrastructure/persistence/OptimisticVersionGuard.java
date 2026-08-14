package com.estoma.authorization.infrastructure.persistence;

final class OptimisticVersionGuard {

  private OptimisticVersionGuard() {}

  static void requireNext(int expectedVersion, int actualVersion) {
    if (actualVersion != expectedVersion + 1) {
      throw new IllegalArgumentException("updated version must equal expectedVersion + 1");
    }
  }
}
