package com.estoma.authentication.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.estoma.authentication.contracts.Actor;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractAccountAuthorizationPolicyTest {

  private final ContractAccountAuthorizationPolicy policy = new ContractAccountAuthorizationPolicy();

  @Test
  void rejectsAnUnknownProducer() {
    assertRejected(envelope("users-service", new Actor("ACCOUNT", UUID.randomUUID(), null, "SELF", null), UUID.randomUUID()));
  }

  @Test
  void rejectsAnOperationOutsideTheCommandContract() {
    UUID accountId = UUID.randomUUID();
    MessageEnvelope envelope = envelope("bff", new Actor("ACCOUNT", accountId, null, "SELF", null), accountId);
    envelope = new MessageEnvelope(
        envelope.messageId(), "DeleteAccount", envelope.messageVersion(), envelope.messageKind(), envelope.producer(),
        envelope.occurredAt(), envelope.correlationId(), envelope.causationId(), envelope.operationId(),
        envelope.idempotencyKey(), envelope.aggregateId(), envelope.aggregateVersion(), envelope.actor(), envelope.payload());

    assertRejected(envelope);
  }

  @Test
  void rejectsAnInvalidActor() {
    assertRejected(envelope("bff", new Actor("SYSTEM", null, "communications", "GLOBAL", null), UUID.randomUUID()));
  }

  @Test
  void rejectsAnUndeclaredScope() {
    UUID accountId = UUID.randomUUID();
     assertRejected(envelope("bff", new Actor("ACCOUNT", accountId, null, "SELF", null), accountId));
  }

  @Test
  void rejectsAnAccountActorWhoseTargetDoesNotMatchItsScope() {
    assertRejected(envelope("bff", new Actor("ACCOUNT", UUID.randomUUID(), null, "SELF", null), UUID.randomUUID()));
  }

  @Test
  void permitsAcademicStudentImportToDeactivateAnAggregate() {
    UUID accountId = UUID.randomUUID();
    assertThatCode(() -> policy.check(new MessageEnvelope(
        UUID.randomUUID(), "DeactivateAccount", 1, MessageKind.COMMAND, "student-import-process", Instant.now(),
        UUID.randomUUID(), null, UUID.randomUUID(), "test", accountId, 1,
        new Actor("SYSTEM", null, "academic", "GLOBAL", null), null))).doesNotThrowAnyException();
  }

  @Test
  void rejectsAcademicDeactivateWithoutAnAggregate() {
    assertRejected(new MessageEnvelope(
        UUID.randomUUID(), "DeactivateAccount", 1, MessageKind.COMMAND, "student-import-process", Instant.now(),
        UUID.randomUUID(), null, UUID.randomUUID(), "test", null, null,
        new Actor("SYSTEM", null, "academic", "GLOBAL", null), null));
  }

  @Test
  void rejectsAcademicDeactivateFromAnotherProducer() {
    assertRejected(new MessageEnvelope(
        UUID.randomUUID(), "DeactivateAccount", 1, MessageKind.COMMAND, "academic-service", Instant.now(),
        UUID.randomUUID(), null, UUID.randomUUID(), "test", UUID.randomUUID(), 1,
        new Actor("SYSTEM", null, "academic", "GLOBAL", null), null));
  }

  @Test
  void academicOnlyAuthorizesAccountLifecycleCommands() {
    assertThat(com.estoma.authentication.contracts.AuthenticationAuthorizationMatrix.rules().stream()
        .filter(rule -> "student-import-process".equals(rule.producer()))
        .map(com.estoma.authentication.contracts.AuthenticationAuthorizationMatrix.Rule::operation)
        .collect(java.util.stream.Collectors.toSet()))
        .isEqualTo(Set.of("CreateAccount", "ActivateAccount", "DeactivateAccount"));
  }

  private void assertRejected(MessageEnvelope envelope) {
    assertThatThrownBy(() -> policy.check(envelope)).isInstanceOf(AuthenticationRejectedException.class);
  }

  private static MessageEnvelope envelope(String producer, Actor actor, UUID aggregateId) {
    return new MessageEnvelope(
        UUID.randomUUID(), "ActivateAccount", 1, MessageKind.COMMAND, producer, Instant.now(), UUID.randomUUID(), null,
        UUID.randomUUID(), "test", aggregateId, 1, actor, null);
  }
}
