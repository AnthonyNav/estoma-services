package com.estoma.authentication.application;

import com.estoma.authentication.contracts.AuthenticationAuthorizationMatrix;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import org.springframework.stereotype.Component;

/**
 * Enforces the technical contract, not business roles or permissions.
 */
@Component
public class ContractAccountAuthorizationPolicy implements AccountAuthorizationPolicy {

  @Override
  public void check(MessageEnvelope envelope) {
    AuthenticationAuthorizationMatrix.Decision decision = AuthenticationAuthorizationMatrix.decision(envelope);
    if (decision != AuthenticationAuthorizationMatrix.Decision.AUTHORIZED) {
      reject(envelope.messageType(), decision);
    }
    if (!AuthenticationAuthorizationMatrix.payloadMatchesAggregate(envelope)) {
      reject(envelope.messageType());
    }
  }

  private static void reject(String operation) {
    reject(operation, AuthenticationAuthorizationMatrix.Decision.ACTOR_NOT_AUTHORIZED);
  }

  private static void reject(String operation, AuthenticationAuthorizationMatrix.Decision decision) {
    RejectionCode code = decision == AuthenticationAuthorizationMatrix.Decision.PRODUCER_NOT_AUTHORIZED
        ? RejectionCode.PRODUCER_NOT_AUTHORIZED : RejectionCode.ACTOR_NOT_AUTHORIZED;
    throw new AuthenticationRejectedException(code, "request is not contractually valid for " + operation);
  }
}
