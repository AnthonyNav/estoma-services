package com.estoma.authorization.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.authorization.application.port.AuthorizationAuditRepository;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.application.port.CommandIdempotencyRepository.IdempotencyRecord;
import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.ProcessedMessageRepository;
import com.estoma.authorization.application.port.StoredApplicationResultDecoder;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.RoleCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AuthorizationCommandProcessorTest {

  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

  @Test
  void concurrentTerminalDelegateOutcomeEmitsItsOwnReplayResult() {
    AuthorizationCommandDispatcher delegate = mock(AuthorizationCommandDispatcher.class);
    ProcessedMessageRepository inbox = mock(ProcessedMessageRepository.class);
    CommandIdempotencyRepository idempotency = mock(CommandIdempotencyRepository.class);
    StoredApplicationResultDecoder decoder = mock(StoredApplicationResultDecoder.class);
    OutboxRepository outbox = mock(OutboxRepository.class);
    AuthorizationAuditRepository audit = mock(AuthorizationAuditRepository.class);
    ProjectionRecoveryCoordinator recovery = mock(ProjectionRecoveryCoordinator.class);
    IdempotencyRecord terminalRecord = mock(IdempotencyRecord.class);
    ApplicationResult result = ApplicationResult.succeeded(Map.of(
        "accountId", UUID.randomUUID(), "roleCode", "ADMINISTRADOR_PRACTICAS", "changed", true, "newVersion", 1));
    UUID messageId = UUID.randomUUID();
    String idempotencyKey = UUID.randomUUID().toString();
    CommandMetadata metadata = new CommandMetadata(
        messageId,
        "AssignAccountRole",
        1,
        "bff",
        NOW,
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        idempotencyKey,
        ActorRef.account(UUID.randomUUID()),
        null,
        null);
    AssignAccountRoleCommand command = new AssignAccountRoleCommand(
        UUID.randomUUID(), RoleCode.ADMINISTRADOR_PRACTICAS, null);
    when(inbox.find("authorization-commands", messageId)).thenReturn(Optional.empty());
    when(idempotency.find("bff", "AssignAccountRole", idempotencyKey))
        .thenReturn(Optional.empty(), Optional.of(terminalRecord));
    when(delegate.dispatch(eq(metadata), eq(command)))
        .thenReturn(CommandOutcome.terminal(result, List.of(), List.of()));
    when(decoder.decode(terminalRecord)).thenReturn(result);

    CommandOutcome outcome = new AuthorizationCommandProcessor(
        immediateTransactions(),
        delegate,
        inbox,
        idempotency,
        decoder,
        outbox,
        audit,
        recovery,
        failure -> false,
        Clock.fixed(NOW, ZoneOffset.UTC)).dispatch(metadata, command);

    assertThat(outcome.result()).contains(result);
    assertThat(outcome.outboundResults()).hasSize(1);
    verify(outbox).append(any());
    verify(inbox).markApplied("authorization-commands", messageId, NOW, NOW);
  }

  private static TransactionRunner immediateTransactions() {
    return new TransactionRunner() {
      @Override
      public <T> T inTransaction(Supplier<T> operation) {
        return operation.get();
      }
    };
  }
}
