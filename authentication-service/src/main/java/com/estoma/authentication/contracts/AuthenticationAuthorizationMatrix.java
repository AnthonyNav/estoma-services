package com.estoma.authentication.contracts;

import java.util.List;

/** The V1 technical authorization matrix for every inbound Authentication operation. */
public final class AuthenticationAuthorizationMatrix {

  public enum ActorMode {
    ANONYMOUS,
    ACCOUNT,
    SYSTEM
  }

  public enum TargetRule {
    REQUIRED,
    FORBIDDEN,
    ACCOUNT_COMPATIBLE
  }

  public enum Decision {
    AUTHORIZED,
    PRODUCER_NOT_AUTHORIZED,
    ACTOR_NOT_AUTHORIZED
  }

  public record Rule(
      MessageKind messageKind,
      String operation,
      String producer,
      ActorMode actorMode,
      String scope,
      String actorService,
      String actorSystemId,
      TargetRule targetRule) {}

  private static final List<Rule> RULES = List.of(
       adminDurable(AuthenticationCommand.CREATE_ACCOUNT),
       personnelDurable(AuthenticationCommand.CREATE_ACCOUNT),
       academic(AuthenticationCommand.CREATE_ACCOUNT),
        adminDurable(AuthenticationCommand.ACTIVATE_ACCOUNT),
        personnelDurable(AuthenticationCommand.ACTIVATE_ACCOUNT),
        academic(AuthenticationCommand.ACTIVATE_ACCOUNT),
         adminDurable(AuthenticationCommand.DEACTIVATE_ACCOUNT),
         personnelDurable(AuthenticationCommand.DEACTIVATE_ACCOUNT),
        academic(AuthenticationCommand.DEACTIVATE_ACCOUNT),
        adminDurable(AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER),
        personnelDurable(AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER),
       adminDurable(AuthenticationCommand.REVOKE_ALL_ACCOUNT_SESSIONS),
       personnelDurable(AuthenticationCommand.REVOKE_ALL_ACCOUNT_SESSIONS),
      durable(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY, "bff", ActorMode.ANONYMOUS, "PUBLIC", null, null, TargetRule.FORBIDDEN),
      rpc(AuthenticationRpcOperation.AUTHENTICATE_ACCOUNT, "bff", ActorMode.ANONYMOUS, "PUBLIC", null, null, TargetRule.FORBIDDEN),
       rpc(AuthenticationRpcOperation.REFRESH_SESSION, "bff", ActorMode.ACCOUNT, "SELF", null, null, TargetRule.ACCOUNT_COMPATIBLE),
      rpc(AuthenticationRpcOperation.REVOKE_SESSION, "bff", ActorMode.ACCOUNT, "SELF", null, null, TargetRule.ACCOUNT_COMPATIBLE),
      rpc(AuthenticationRpcOperation.VALIDATE_RECOVERY_CODE, "bff", ActorMode.ANONYMOUS, "PUBLIC", null, null, TargetRule.FORBIDDEN),
      rpc(AuthenticationRpcOperation.RESET_PASSWORD, "bff", ActorMode.ANONYMOUS, "PUBLIC", null, null, TargetRule.FORBIDDEN),
      rpc(AuthenticationRpcOperation.CHANGE_PASSWORD, "bff", ActorMode.ACCOUNT, "SELF", null, null, TargetRule.ACCOUNT_COMPATIBLE),
        adminRpc(AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD),
        personnelRpc(AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD),
        adminRpc(AuthenticationRpcOperation.REGENERATE_TEMPORARY_PASSWORD),
        personnelRpc(AuthenticationRpcOperation.REGENERATE_TEMPORARY_PASSWORD),
       rpc(AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY, "communications", ActorMode.SYSTEM, "GLOBAL", "communications", null, TargetRule.FORBIDDEN));

  private AuthenticationAuthorizationMatrix() {}

  public static List<Rule> rules() {
    return RULES;
  }

  public static boolean permits(MessageEnvelope envelope) {
    return RULES.stream().anyMatch(rule -> matches(rule, envelope));
  }

  private static Rule durable(
      AuthenticationCommand operation, String producer, ActorMode actorMode, String scope, String actorService, String actorSystemId, TargetRule targetRule) {
    return new Rule(MessageKind.COMMAND, operation.wireName(), producer, actorMode, scope, actorService, actorSystemId, targetRule);
  }

  private static Rule adminDurable(AuthenticationCommand operation) {
     return durable(operation, "bff", ActorMode.ACCOUNT, "GLOBAL", null, null, TargetRule.REQUIRED);
  }

  private static Rule personnelDurable(AuthenticationCommand operation) {
     return durable(operation, "bff", ActorMode.ACCOUNT, "SYSTEM_PERSONNEL", null, "*", TargetRule.REQUIRED);
  }

  private static Rule academic(AuthenticationCommand operation) {
     return durable(operation, "student-import-process", ActorMode.SYSTEM, "GLOBAL", "academic", null, TargetRule.REQUIRED);
  }

  private static Rule rpc(
      AuthenticationRpcOperation operation, String producer, ActorMode actorMode, String scope, String actorService, String actorSystemId, TargetRule targetRule) {
    return new Rule(MessageKind.RPC_REQUEST, operation.wireName(), producer, actorMode, scope, actorService, actorSystemId, targetRule);
  }

  private static Rule adminRpc(AuthenticationRpcOperation operation) {
     return rpc(operation, "bff", ActorMode.ACCOUNT, "GLOBAL", null, null, TargetRule.REQUIRED);
  }

  private static Rule personnelRpc(AuthenticationRpcOperation operation) {
     return rpc(operation, "bff", ActorMode.ACCOUNT, "SYSTEM_PERSONNEL", null, "*", TargetRule.REQUIRED);
  }

  private static boolean matches(Rule rule, MessageEnvelope envelope) {
    Actor actor = envelope.actor();
    return rule.messageKind() == envelope.messageKind()
        && rule.operation().equals(envelope.messageType())
        && rule.producer().equals(envelope.producer())
        && actorMatches(rule, actor)
        && targetMatches(rule.targetRule(), envelope, actor);
  }

  public static Decision decision(MessageEnvelope envelope) {
    if (envelope == null) {
      return Decision.ACTOR_NOT_AUTHORIZED;
    }
    List<Rule> operations = RULES.stream()
        .filter(rule -> rule.messageKind() == envelope.messageKind() && rule.operation().equals(envelope.messageType()))
        .toList();
    if (operations.stream().noneMatch(rule -> rule.producer().equals(envelope.producer()))) {
      return Decision.PRODUCER_NOT_AUTHORIZED;
    }
    return operations.stream().anyMatch(rule -> rule.producer().equals(envelope.producer()) && matches(rule, envelope))
        ? Decision.AUTHORIZED : Decision.ACTOR_NOT_AUTHORIZED;
  }

  public static boolean payloadMatchesAggregate(MessageEnvelope envelope) {
    if (envelope == null || envelope.payload() == null || envelope.aggregateId() == null) {
      return true;
    }
    var accountId = envelope.payload().get("accountId");
    return accountId == null || (accountId.isTextual() && envelope.aggregateId().toString().equals(accountId.asString()));
  }

  private static boolean actorMatches(Rule rule, Actor actor) {
    if (actor == null || !rule.scope().equals(actor.scope())) {
      return false;
    }
    return switch (rule.actorMode()) {
      case ANONYMOUS -> "ANONYMOUS".equals(actor.type()) && actor.accountId() == null && actor.service() == null && actor.systemId() == null;
      case ACCOUNT -> "ACCOUNT".equals(actor.type()) && actor.accountId() != null && actor.service() == null
          && ("SYSTEM_PERSONNEL".equals(rule.scope()) ? actor.systemId() != null && !actor.systemId().isBlank() : actor.systemId() == null);
      case SYSTEM -> "SYSTEM".equals(actor.type())
          && actor.accountId() == null
          && rule.actorService().equals(actor.service())
          && ("*".equals(rule.actorSystemId()) ? actor.systemId() != null && !actor.systemId().isBlank()
              : java.util.Objects.equals(rule.actorSystemId(), actor.systemId()));
    };
  }

  private static boolean targetMatches(TargetRule targetRule, MessageEnvelope envelope, Actor actor) {
    return switch (targetRule) {
      case REQUIRED -> envelope.aggregateId() != null;
      case FORBIDDEN -> envelope.aggregateId() == null;
      case ACCOUNT_COMPATIBLE -> envelope.aggregateId() != null && envelope.aggregateId().equals(actor.accountId());
    };
  }
}
