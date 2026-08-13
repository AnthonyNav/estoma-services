package com.estoma.washadministration.infrastructure.persistence.jdbc;

final class OptimisticVersionGuard {

  private OptimisticVersionGuard() {}

  static void requireNext(int expectedVersion, int candidateVersion) {
    if (expectedVersion <= 0 || candidateVersion != expectedVersion + 1) {
      throw new IllegalArgumentException(
          "candidate version must be exactly expectedVersion + 1");
    }
  }
}
