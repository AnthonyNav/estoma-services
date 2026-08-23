package com.estoma.platformbff.application.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.commands.CommandRegistry;
import com.estoma.platformbff.application.port.ClientOperationRepository;
import com.estoma.platformbff.application.port.IdempotencyRepository;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.OutboxRepository;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import com.estoma.platformbff.domain.operation.ClientOperation;
import com.estoma.platformbff.domain.operation.OperationStatus;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ResultApplicationServiceTest {
  @Mock ClientOperationRepository operations;
  @Mock InboxRepository inbox;
  @Mock OutboxRepository outbox;
  @Mock IdempotencyRepository idempotency;

  private ResultApplicationService service;
  private UUID operationId;
  private UUID correlationId;
  private UUID commandMessageId;
  private ClientOperation pending;

  @BeforeEach
  void setUp() {
    service = new ResultApplicationService(
        operations, inbox, outbox, idempotency, new CommandRegistry(), Objects::equals, 24L);
    operationId = UUID.randomUUID();
    correlationId = UUID.randomUUID();
    commandMessageId = UUID.randomUUID();
    pending = operation(OperationStatus.PENDING, null, null, null);
    when(operations.findById(operationId)).thenReturn(Optional.of(pending));
    when(outbox.findMessageIdByOperationId(operationId)).thenReturn(Optional.of(commandMessageId));
  }

  @Test
  void terminalResultCommitsThroughInboxAndStartsIdempotencyRetention() {
    when(inbox.recordIfNew(any(InboxMessage.class))).thenReturn(true);
    when(operations.completeIfPending(any(), any(), any())).thenReturn(true);
    when(idempotency.ensureExpiresAt(any(), any())).thenReturn(true);

    assertThat(service.apply(succeeded())).isTrue();

    verify(inbox).recordIfNew(any());
    verify(operations).completeIfPending(any(), any(), any());
    verify(idempotency).ensureExpiresAt(any(), any());
  }

  @Test
  void exactDuplicateDeliveryDoesNotApplyAgain() {
    when(inbox.recordIfNew(any(InboxMessage.class))).thenReturn(false);

    assertThat(service.apply(succeeded())).isTrue();

    verify(operations, never()).completeIfPending(any(), any(), any());
    verify(idempotency, never()).ensureExpiresAt(any(), any());
  }

  @Test
  void rejectsWrongCausationBeforeInbox() {
    IncomingResult invalid = new IncomingResult(
        UUID.randomUUID(), "CreatePersonSucceeded", 1, MessageKind.RESULT, "users",
        Instant.now(), correlationId, UUID.randomUUID(), operationId, null, null,
        "SUCCEEDED", "{\"personId\":\"p\"}", null, null);

    assertThat(service.apply(invalid)).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void rejectsRejectedResultWithoutErrorCodeBeforeInbox() {
    IncomingResult invalid = new IncomingResult(
        UUID.randomUUID(), "CreatePersonRejected", 1, MessageKind.RESULT, "users",
        Instant.now(), correlationId, commandMessageId, operationId, null, null,
        "REJECTED", null, null, "{}");

    assertThat(service.apply(invalid)).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void rejectsSucceededResultThatCarriesErrorBeforeInbox() {
    IncomingResult invalid = new IncomingResult(
        UUID.randomUUID(), "CreatePersonSucceeded", 1, MessageKind.RESULT, "users",
        Instant.now(), correlationId, commandMessageId, operationId, null, null,
        "SUCCEEDED", "{\"personId\":\"p\"}", "UNEXPECTED",
        "{\"code\":\"UNEXPECTED\"}");

    assertThat(service.apply(invalid)).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void rejectsFailedResultWithoutErrorCodeBeforeInbox() {
    ClientOperation authenticationPending = operationFor(
        "create-account", "CreateAccount", "authentication", OperationStatus.PENDING,
        null, null, null);
    when(operations.findById(operationId)).thenReturn(Optional.of(authenticationPending));

    IncomingResult invalid = new IncomingResult(
        UUID.randomUUID(), "CreateAccountResult", 1, MessageKind.RESULT, "authentication",
        Instant.now(), correlationId, commandMessageId, operationId, null, null,
        "FAILED", null, null, "{}");

    assertThat(service.apply(invalid)).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void expiredResultMayOmitError() {
    ClientOperation authenticationPending = operationFor(
        "create-account", "CreateAccount", "authentication", OperationStatus.PENDING,
        null, null, null);
    when(operations.findById(operationId)).thenReturn(Optional.of(authenticationPending));
    when(inbox.recordIfNew(any(InboxMessage.class))).thenReturn(true);
    when(operations.completeIfPending(any(), any(), any())).thenReturn(true);
    when(idempotency.ensureExpiresAt(any(), any())).thenReturn(true);

    IncomingResult expired = new IncomingResult(
        UUID.randomUUID(), "CreateAccountResult", 1, MessageKind.RESULT, "authentication",
        Instant.now(), correlationId, commandMessageId, operationId, null, null,
        "EXPIRED", null, null, null);

    assertThat(service.apply(expired)).isTrue();
    verify(inbox).recordIfNew(any());
    verify(operations).completeIfPending(any(), any(), any());
  }

  @Test
  void exactLateTerminalResultIsIdempotent() {
    ClientOperation terminal = operation(
        OperationStatus.SUCCEEDED, "{\"personId\":\"p\"}", null, null);
    when(operations.findById(operationId)).thenReturn(Optional.of(terminal));
    when(inbox.recordIfNew(any(InboxMessage.class))).thenReturn(true);
    when(idempotency.ensureExpiresAt(any(), any())).thenReturn(true);

    assertThat(service.apply(succeeded())).isTrue();

    verify(operations, never()).completeIfPending(any(), any(), any());
    verify(idempotency).ensureExpiresAt(any(), any());
  }

  @Test
  void contradictoryLateTerminalResultIsUntrusted() {
    ClientOperation terminal = operation(OperationStatus.REJECTED, null, "OWNER_REJECTED",
        "{\"code\":\"OWNER_REJECTED\"}");
    when(operations.findById(operationId)).thenReturn(Optional.of(terminal));

    assertThat(service.apply(succeeded())).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void lostTerminalRaceRollsBackWhenWinnerContradictsResult() {
    when(inbox.recordIfNew(any(InboxMessage.class))).thenReturn(true);
    when(operations.completeIfPending(any(), any(), any())).thenReturn(false);
    ClientOperation contradictory = operation(OperationStatus.REJECTED, null, "OWNER_REJECTED",
        "{\"code\":\"OWNER_REJECTED\"}");
    when(operations.findById(operationId))
        .thenReturn(Optional.of(pending), Optional.of(contradictory));

    assertThatThrownBy(() -> service.apply(succeeded()))
        .isInstanceOf(ResultApplicationService.ConflictingTerminalResultException.class);
  }

  @Test
  void retentionConfigurationCannotUndercutV1Minimum() {
    assertThatThrownBy(() -> new ResultApplicationService(
        operations, inbox, outbox, idempotency, new CommandRegistry(), Objects::equals, 23L))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private IncomingResult succeeded() {
    return new IncomingResult(
        UUID.randomUUID(), "CreatePersonSucceeded", 1, MessageKind.RESULT, "users",
        Instant.now(), correlationId, commandMessageId, operationId, null, null,
        "SUCCEEDED", "{\"personId\":\"p\"}", null, null);
  }

  private ClientOperation operation(
      OperationStatus status, String data, String errorCode, String errorDetails) {
    return operationFor("create-person", "CreatePerson", "users", status, data, errorCode,
        errorDetails);
  }

  private ClientOperation operationFor(
      String publicOperation,
      String commandType,
      String owner,
      OperationStatus status,
      String data,
      String errorCode,
      String errorDetails) {
    Instant now = Instant.now();
    return new ClientOperation(
        operationId, UUID.randomUUID(), publicOperation, commandType, owner, owner,
        correlationId, "key", status, data, errorCode, errorDetails,
        now, now, status.terminal() ? now : null, null);
  }
}
