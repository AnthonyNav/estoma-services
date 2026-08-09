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
      durable(AuthenticationCommand.CREATE_ACCOUNT, "bff", ActorMode.SYSTEM, "GLOBAL", "bff", null, TargetRule.REQUIRED),
      durable(AuthenticationCommand.CREATE_ACCOUNT, "academic", ActorMode.SYSTEM, "GLOBAL", "academic", "student-import-process", TargetRule.REQUIRED),
      durable(AuthenticationCommand.ACTIVATE_ACCOUNT, "bff", ActorMode.SYSTEM, "GLOBAL", "bff", null, TargetRule.REQUIRED),
      durable(AuthenticationCommand.DEACTIVATE_ACCOUNT, "bff", ActorMode.SYSTEM, "GLOBAL", "bff", null, TargetRule.REQUIRED),
      durable(AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER, "bff", ActorMode.SYSTEM, "GLOBAL", "bff", null, TargetRule.REQUIRED),
      durable(AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER, "academic", ActorMode.SYSTEM, "GLOBAL", "academic", "student-import-process", TargetRule.REQUIRED),
      durable(AuthenticationCommand.REVOKE_ALL_ACCOUNT_SESSIONS, "bff", ActorMode.SYSTEM, "GLOBAL", "bff", null, TargetRule.REQUIRED),
      durable(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY, "bff", ActorMode.ANONYMOUS, "PUBLIC", null, null, TargetRule.FORBIDDEN),
      rpc(AuthenticationRpcOperation.AUTHENTICATE_ACCOUNT, "bff", ActorMode.ANONYMOUS, "PUBLIC", null, null, TargetRule.FORBIDDEN),
      rpc(AuthenticationRpcOperation.REFRESH_SESSION, "bff", ActorMode.ACCOUNT, "SELF", null, null, TargetRule.FORBIDDEN),
      rpc(AuthenticationRpcOperation.REVOKE_SESSION, "bff", ActorMode.ACCOUNT, "SELF", null, null, TargetRule.ACCOUNT_COMPATIBLE),
      rpc(AuthenticationRpcOperation.VALIDATE_RECOVERY_CODE, "bff", ActorMode.ANONYMOUS, "PUBLIC", null, null, TargetRule.FORBIDDEN),
      rpc(AuthenticationRpcOperation.RESET_PASSWORD, "bff", ActorMode.ANONYMOUS, "PUBLIC", null, null, TargetRule.FORBIDDEN),
      rpc(AuthenticationRpcOperation.CHANGE_PASSWORD, "bff", ActorMode.ACCOUNT, "SELF", null, null, TargetRule.ACCOUNT_COMPATIBLE),
      rpc(AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD, "bff", ActorMode.SYSTEM, "GLOBAL", "bff", null, TargetRule.REQUIRED),
      rpc(AuthenticationRpcOperation.REGENERATE_TEMPORARY_PASSWORD, "bff", ActorMode.SYSTEM, "GLOBAL", "bff", null, TargetRule.REQUIRED),
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

  private static Rule rpc(
      AuthenticationRpcOperation operation, String producer, ActorMode actorMode, String scope, String actorService, String actorSystemId, TargetRule targetRule) {
    return new Rule(MessageKind.RPC_REQUEST, operation.wireName(), producer, actorMode, scope, actorService, actorSystemId, targetRule);
  }

  private static boolean matches(Rule rule, MessageEnvelope envelope) {
    Actor actor = envelope.actor();
    return rule.messageKind() == envelope.messageKind()
        && rule.operation().equals(envelope.messageType())
        && rule.producer().equals(envelope.producer())
        && actorMatches(rule, actor)
        && targetMatches(rule.targetRule(), envelope, actor);
  }

  private static boolean actorMatches(Rule rule, Actor actor) {
    if (actor == null || !rule.scope().equals(actor.scope())) {
      return false;
    }
    return switch (rule.actorMode()) {
      case ANONYMOUS -> "ANONYMOUS".equals(actor.type()) && actor.accountId() == null && actor.service() == null && actor.systemId() == null;
      case ACCOUNT -> "ACCOUNT".equals(actor.type()) && actor.accountId() != null && actor.service() == null && actor.systemId() == null;
      case SYSTEM -> "SYSTEM".equals(actor.type())
          && actor.accountId() == null
          && rule.actorService().equals(actor.service())
          && java.util.Objects.equals(rule.actorSystemId(), actor.systemId());
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
