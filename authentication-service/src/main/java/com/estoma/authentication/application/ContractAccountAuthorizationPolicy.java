package com.estoma.authentication.application;

import com.estoma.authentication.contracts.AuthenticationAuthorizationMatrix;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Enforces the technical contract, not business roles or permissions. */
@Component
public class ContractAccountAuthorizationPolicy implements AccountAuthorizationPolicy {

  private static final Set<String> ACADEMIC_OPERATIONS = Set.of(
      "CreateAccount", "ActivateAccount", "DeactivateAccount");

  @Override
  public void check(MessageEnvelope envelope) {
    AuthenticationAuthorizationMatrix.Decision decision = AuthenticationAuthorizationMatrix.decision(envelope);
    if (decision != AuthenticationAuthorizationMatrix.Decision.AUTHORIZED) {
      reject(envelope.messageType(), decision);
    }
    if (!AuthenticationAuthorizationMatrix.payloadMatchesAggregate(envelope)) {
      reject(envelope.messageType());
    }
    if ("academic".equals(envelope.producer())) {
      validateImportExecution(envelope);
    }
  }

  private static void validateImportExecution(MessageEnvelope envelope) {
    if (!ACADEMIC_OPERATIONS.contains(envelope.messageType())) {
      reject(envelope.messageType(), AuthenticationAuthorizationMatrix.Decision.PRODUCER_NOT_AUTHORIZED);
    }
    JsonNode execution = envelope.payload().path("importExecution");
    requireUuid(execution, "importId", envelope.messageType());
    requireUuid(execution, "planItemId", envelope.messageType());
    requireUuid(execution, "authorizedByAccountId", envelope.messageType());
    if (execution.path("executionGeneration").asInt(-1) < 1) {
      reject(envelope.messageType());
    }
    String expectedScope = switch (envelope.messageType()) {
      case "CreateAccount" -> "CREATE_ACCOUNT";
      case "ActivateAccount" -> "ACTIVATE_ACCOUNT";
      case "DeactivateAccount" -> "DEACTIVATE_ACCOUNT";
      default -> throw new IllegalStateException();
    };
    if (!expectedScope.equals(execution.path("scope").asString(null))) {
      reject(envelope.messageType());
    }
  }

  private static void requireUuid(JsonNode node, String field, String operation) {
    try {
      UUID.fromString(node.path(field).asString());
    } catch (RuntimeException invalid) {
      reject(operation);
    }
  }

  private static void reject(String operation) {
    reject(operation, AuthenticationAuthorizationMatrix.Decision.ACTOR_NOT_AUTHORIZED);
  }

  private static void reject(String operation, AuthenticationAuthorizationMatrix.Decision decision) {
    RejectionCode code = decision == AuthenticationAuthorizationMatrix.Decision.PRODUCER_NOT_AUTHORIZED
        ? RejectionCode.PRODUCER_NOT_AUTHORIZED
        : RejectionCode.ACTOR_NOT_AUTHORIZED;
    throw new AuthenticationRejectedException(code, "request is not contractually valid for " + operation);
  }
}
