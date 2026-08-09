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
    if (!AuthenticationAuthorizationMatrix.permits(envelope)) {
      reject(envelope.messageType());
    }
  }

  private static void reject(String operation) {
    throw new AuthenticationRejectedException(RejectionCode.ACTOR_NOT_AUTHORIZED, "actor is not contractually valid for " + operation);
  }
}
