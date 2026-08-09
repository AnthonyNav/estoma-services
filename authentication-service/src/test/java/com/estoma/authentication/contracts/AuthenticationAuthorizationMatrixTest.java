package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AuthenticationAuthorizationMatrixTest {

  @Test
  void permitsEveryExplicitAuthorizationRule() {
    for (AuthenticationAuthorizationMatrix.Rule rule : AuthenticationAuthorizationMatrix.rules()) {
      assertThat(AuthenticationAuthorizationMatrix.permits(envelope(rule))).as(rule.toString()).isTrue();
    }
  }

  @Test
  void rejectsAProducerThatIsNotAuthorizedForTheOperation() {
    AuthenticationAuthorizationMatrix.Rule rule = AuthenticationAuthorizationMatrix.rules().getFirst();
    MessageEnvelope envelope = envelope(rule);
    envelope = copy(envelope, "communications", envelope.actor(), envelope.aggregateId());

    assertThat(AuthenticationAuthorizationMatrix.permits(envelope)).isFalse();
  }

  @Test
  void rejectsAnAccountTargetThatDoesNotMatchSelfScope() {
    AuthenticationAuthorizationMatrix.Rule rule = AuthenticationAuthorizationMatrix.rules().stream()
        .filter(candidate -> candidate.targetRule() == AuthenticationAuthorizationMatrix.TargetRule.ACCOUNT_COMPATIBLE)
        .findFirst()
        .orElseThrow();

    assertThat(AuthenticationAuthorizationMatrix.permits(copy(envelope(rule), rule.producer(), accountActor(UUID.randomUUID(), rule.scope()), UUID.randomUUID())))
        .isFalse();
  }

  @Test
  void rejectsAForbiddenPublicTarget() {
    AuthenticationAuthorizationMatrix.Rule rule = AuthenticationAuthorizationMatrix.rules().stream()
        .filter(candidate -> candidate.targetRule() == AuthenticationAuthorizationMatrix.TargetRule.FORBIDDEN)
        .findFirst()
        .orElseThrow();

    assertThat(AuthenticationAuthorizationMatrix.permits(copy(envelope(rule), rule.producer(), actor(rule, null), UUID.randomUUID()))).isFalse();
  }

  private static MessageEnvelope envelope(AuthenticationAuthorizationMatrix.Rule rule) {
    UUID target = switch (rule.targetRule()) {
      case REQUIRED -> UUID.randomUUID();
      case FORBIDDEN -> null;
      case ACCOUNT_COMPATIBLE -> UUID.randomUUID();
    };
    return copy(
        new MessageEnvelope(UUID.randomUUID(), rule.operation(), 1, rule.messageKind(), rule.producer(), Instant.now(),
            UUID.randomUUID(), null, null, null, target, null, null, null),
        rule.producer(), actor(rule, target), target);
  }

  private static MessageEnvelope copy(MessageEnvelope envelope, String producer, Actor actor, UUID target) {
    return new MessageEnvelope(
        envelope.messageId(), envelope.messageType(), envelope.messageVersion(), envelope.messageKind(), producer,
        envelope.occurredAt(), envelope.correlationId(), envelope.causationId(), envelope.operationId(), envelope.idempotencyKey(),
        target, envelope.aggregateVersion(), actor, envelope.payload());
  }

  private static Actor actor(AuthenticationAuthorizationMatrix.Rule rule, UUID target) {
    return switch (rule.actorMode()) {
      case ANONYMOUS -> new Actor("ANONYMOUS", null, null, rule.scope(), null);
      case ACCOUNT -> new Actor("ACCOUNT", target != null ? target : UUID.randomUUID(), null, rule.scope(),
          "SYSTEM_PERSONNEL".equals(rule.scope()) ? "personnel" : null);
      case SYSTEM -> new Actor("SYSTEM", null, rule.actorService(), rule.scope(), rule.actorSystemId());
    };
  }

  private static Actor accountActor(UUID accountId, String scope) {
    return new Actor("ACCOUNT", accountId, null, scope, null);
  }
}
