package com.estoma.authentication.application;

import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import org.springframework.stereotype.Component;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enforces the technical contract, not business roles or permissions.
 */
@Component
public class ContractAccountAuthorizationPolicy implements AccountAuthorizationPolicy {

  private static final Set<String> COMMANDS =
      Arrays.stream(AuthenticationCommand.values()).map(AuthenticationCommand::wireName).collect(Collectors.toUnmodifiableSet());

  @Override
  public void check(MessageEnvelope envelope) {
    String operation = envelope.messageType();
    Actor actor = envelope.actor();
    if (!COMMANDS.contains(operation) || !"bff".equals(envelope.producer())) {
      reject(operation);
    }
    if (actor == null || actor.type() == null) {
      reject(operation);
    }
    if ("ACCOUNT".equals(actor.type())
        && actor.accountId() != null
        && "SELF".equals(actor.scope())
        && actor.accountId().equals(envelope.aggregateId())) {
      return;
    }
    if ("SYSTEM".equals(actor.type())
        && "bff".equals(actor.service())
        && "GLOBAL".equals(actor.scope())
        && envelope.aggregateId() != null) {
      return;
    }
    reject(operation);
  }

  private static void reject(String operation) {
    throw new AuthenticationRejectedException(RejectionCode.ACTOR_NOT_AUTHORIZED, "actor is not contractually valid for " + operation);
  }
}
