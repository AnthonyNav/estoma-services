package com.estoma.authorization.application.command;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record ImportExecution(
    UUID importId,
    UUID planItemId,
    int executionGeneration,
    UUID authorizedByAccountId,
    String scope,
    List<CausalDependency> causalDependencies,
    List<AuthoritativeEvidence> authoritativeEvidence) {

  public ImportExecution {
    Objects.requireNonNull(importId, "importId");
    Objects.requireNonNull(planItemId, "planItemId");
    Objects.requireNonNull(authorizedByAccountId, "authorizedByAccountId");
    if (executionGeneration <= 0) {
      throw new IllegalArgumentException("executionGeneration must be positive");
    }
    if (scope == null || scope.isBlank()) {
      throw new IllegalArgumentException("scope must be non-blank");
    }
    causalDependencies = List.copyOf(causalDependencies == null ? List.of() : causalDependencies);
    authoritativeEvidence = List.copyOf(authoritativeEvidence == null ? List.of() : authoritativeEvidence);
  }

  public static ImportExecution of(
      UUID importId,
      UUID planItemId,
      int executionGeneration,
      UUID authorizedByAccountId,
      String scope) {
    return new ImportExecution(
        importId, planItemId, executionGeneration, authorizedByAccountId, scope, List.of(), List.of());
  }
}
