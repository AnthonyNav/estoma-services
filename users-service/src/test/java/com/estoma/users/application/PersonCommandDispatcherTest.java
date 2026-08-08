package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.application.command.UpdatePersonCommand;
import com.estoma.users.application.handler.HandlerResult;
import com.estoma.users.application.handler.PersonCommandHandler;
import com.estoma.users.application.port.IdempotencyPort;
import com.estoma.users.application.port.InboxPort;
import com.estoma.users.application.port.OutboxAppendRequest;
import com.estoma.users.application.port.OutboxPort;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.contract.event.PersonUpdatedPayload;
import com.estoma.users.contract.result.ResultPayload;
import com.estoma.users.contract.result.ResultStatus;
import com.estoma.users.contract.result.SucceededPayload;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit-level (mocked ports, no Spring context, no Testcontainers) tests
 * for {@link PersonCommandDispatcher}: the {@code aggregateId}/payload
 * {@code personId} consistency check (commands.md §2.1, §3) and the
 * functional idempotency claim/reconstruction protocol (commands.md §9,
 * messaging-architecture.md §10.1). Deliberately not an {@code *IT} test:
 * both are pure functions of the envelope, payload, and the {@link
 * IdempotencyPort} response — exercising them through the full
 * outbox/broker pipeline adds shared-container risk (see git history)
 * for no added coverage. {@code CommandTransactionIT} and {@code
 * PersonStatusHistoryTransactionIT} already prove the real end-to-end
 * persistence path, and {@code JdbcIdempotencyRepositoryIT} proves the
 * real concurrent-claim locking behavior this test only simulates via
 * stubbed responses.
 */
class PersonCommandDispatcherTest {

  private static final String PRODUCER = "bff";
  private static final String IDEMPOTENCY_KEY = "retry-key";

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final InboxPort inbox = mock(InboxPort.class);
  private final OutboxPort outbox = mock(OutboxPort.class);
  private final IdempotencyPort idempotency = mock(IdempotencyPort.class);
  private final PersonCommandHandler createHandler = mock(PersonCommandHandler.class);
  private final PersonCommandHandler updateHandler = mock(PersonCommandHandler.class);

  // Stubbed before the dispatcher field below is constructed: the handler
  // map is built once, in the constructor, from commandMessageType() --
  // stubbing it inside a @Test method would be too late.
  {
    when(createHandler.commandMessageType()).thenReturn(MessageTypes.CREATE_PERSON);
    when(updateHandler.commandMessageType()).thenReturn(MessageTypes.UPDATE_PERSON);
    when(inbox.tryInsert(anyString(), any(), any())).thenReturn(true);
    // Default: every claim() call looks like a brand-new intention --
    // claimedByMessageId always echoes back whatever messageId was just
    // passed in. Individual tests override this for the specific
    // messageId of a simulated retry.
    when(idempotency.claim(any(), any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new IdempotencyRecord(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    invocation.getArgument(5),
                    IdempotencyStatus.IN_PROGRESS,
                    null,
                    null,
                    Instant.now(),
                    null));
  }

  private final PersonCommandDispatcher dispatcher =
      new PersonCommandDispatcher(inbox, outbox, idempotency, codec, List.of(createHandler, updateHandler));

  @Test
  void matchingAggregateIdAndPersonIdInvokesTheHandler() {
    UUID personId = UUID.randomUUID();
    when(updateHandler.handle(any())).thenReturn(succeededResult(personId, 2));

    CommandOutcome outcome = dispatcher.dispatch(envelope(personId, personId, IDEMPOTENCY_KEY));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    verify(updateHandler).handle(any());
    verify(idempotency).markSucceeded(eq(PRODUCER), eq(IDEMPOTENCY_KEY), eq(2), any());

    OutboxAppendRequest resultRow = capturedResultRow();
    ResultPayload payload = codec.readValue(resultRow.payloadJson(), ResultPayload.class);
    assertThat(payload.status()).isEqualTo(ResultStatus.SUCCEEDED);
    assertThat(payload.error()).isNull();
    SucceededPayload data = codec.readValue(codec.writeValueAsString(payload.data()), SucceededPayload.class);
    assertThat(data.personId()).isEqualTo(personId);
    assertThat(data.version()).isEqualTo(2);
  }

  @Test
  void mismatchedAggregateIdIsRejectedWithoutInvokingTheHandlerOrClaimingIdempotency() {
    UUID personId = UUID.randomUUID();
    UUID unrelatedAggregateId = UUID.randomUUID();

    CommandOutcome outcome = dispatcher.dispatch(envelope(unrelatedAggregateId, personId, IDEMPOTENCY_KEY));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.INVALID_MESSAGE);
    verify(updateHandler, never()).handle(any());
    // A structurally invalid request never reaches the idempotency claim
    // -- it's deterministic and has no side effect to protect.
    verify(idempotency, never()).claim(any(), any(), any(), any(), any(), any(), any());

    OutboxAppendRequest resultRow = capturedResultRow();
    assertThat(resultRow.messageType()).isEqualTo("UpdatePersonRejected");
    ResultPayload payload = codec.readValue(resultRow.payloadJson(), ResultPayload.class);
    assertThat(payload.status()).isEqualTo(ResultStatus.REJECTED);
    assertThat(payload.data()).isNull();
    assertThat(payload.error().code()).isEqualTo(RejectionCode.INVALID_MESSAGE.name());
  }

  @Test
  void missingAggregateIdIsRejectedWithoutInvokingTheHandlerOrClaimingIdempotency() {
    UUID personId = UUID.randomUUID();

    CommandOutcome outcome = dispatcher.dispatch(envelope(null, personId, IDEMPOTENCY_KEY));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.INVALID_MESSAGE);
    verify(updateHandler, never()).handle(any());
    verify(idempotency, never()).claim(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void retryWithTheSameIdempotencyKeyAndIntentionReconstructsTheSucceededResultWithoutReinvokingTheHandler() {
    UUID personId = UUID.randomUUID();
    when(updateHandler.handle(any())).thenReturn(succeededResult(personId, 2));

    MessageEnvelope original = envelope(personId, personId, IDEMPOTENCY_KEY);
    CommandOutcome firstOutcome = dispatcher.dispatch(original);
    assertThat(firstOutcome).isInstanceOf(CommandOutcome.Succeeded.class);
    verify(updateHandler, times(1)).handle(any());

    ArgumentCaptor<String> fingerprintCaptor = ArgumentCaptor.forClass(String.class);
    verify(idempotency)
        .claim(eq(PRODUCER), eq(IDEMPOTENCY_KEY), eq(MessageTypes.UPDATE_PERSON), eq(personId), fingerprintCaptor.capture(), eq(original.messageId()), any());
    String fingerprint = fingerprintCaptor.getValue();

    // Same intention (identical functional payload), new messageId --
    // exactly what a retry looks like.
    MessageEnvelope retry = envelope(personId, personId, IDEMPOTENCY_KEY);
    when(idempotency.claim(
            eq(PRODUCER), eq(IDEMPOTENCY_KEY), eq(MessageTypes.UPDATE_PERSON), eq(personId), eq(fingerprint), eq(retry.messageId()), any()))
        .thenReturn(
            new IdempotencyRecord(
                PRODUCER,
                IDEMPOTENCY_KEY,
                MessageTypes.UPDATE_PERSON,
                personId,
                fingerprint,
                original.messageId(), // claimed by the ORIGINAL call, not this retry
                IdempotencyStatus.SUCCEEDED,
                2,
                null,
                Instant.now(),
                Instant.now()));

    CommandOutcome retryOutcome = dispatcher.dispatch(retry);

    assertThat(retryOutcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(((CommandOutcome.Succeeded) retryOutcome).aggregateVersion()).isEqualTo(2);
    verify(updateHandler, times(1)).handle(any()); // still only once total
    verify(idempotency, times(1)).markSucceeded(any(), any(), anyInt(), any()); // never re-marked by the retry

    OutboxAppendRequest reconstructed = capturedResultRowsInOrder().get(1);
    assertThat(reconstructed.messageType()).isEqualTo("UpdatePersonSucceeded");
    assertThat(reconstructed.aggregateVersion()).isEqualTo(2);
    assertThat(reconstructed.causationId()).isEqualTo(retry.messageId());
    assertThat(reconstructed.correlationId()).isEqualTo(retry.correlationId());
    ResultPayload reconstructedPayload = codec.readValue(reconstructed.payloadJson(), ResultPayload.class);
    assertThat(reconstructedPayload.status()).isEqualTo(ResultStatus.SUCCEEDED);
    SucceededPayload reconstructedData =
        codec.readValue(codec.writeValueAsString(reconstructedPayload.data()), SucceededPayload.class);
    assertThat(reconstructedData.version()).isEqualTo(2);
  }

  @Test
  void retryWithTheSameIdempotencyKeyButADifferentIntentionIsRejectedWithoutTouchingTheOriginalClaim() {
    UUID personId = UUID.randomUUID();
    MessageEnvelope retry = envelope(personId, personId, IDEMPOTENCY_KEY);
    when(idempotency.claim(
            eq(PRODUCER), eq(IDEMPOTENCY_KEY), eq(MessageTypes.UPDATE_PERSON), eq(personId), any(), eq(retry.messageId()), any()))
        .thenReturn(
            new IdempotencyRecord(
                PRODUCER,
                IDEMPOTENCY_KEY,
                MessageTypes.UPDATE_PERSON,
                personId,
                "a-completely-different-fingerprint",
                UUID.randomUUID(), // some other, original, message owns this claim
                IdempotencyStatus.SUCCEEDED,
                1,
                null,
                Instant.now(),
                Instant.now()));

    CommandOutcome outcome = dispatcher.dispatch(retry);

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.INVALID_MESSAGE);
    verify(updateHandler, never()).handle(any());
    verify(idempotency, never()).markSucceeded(any(), any(), anyInt(), any());
    verify(idempotency, never()).markRejected(any(), any(), any(), any());

    OutboxAppendRequest resultRow = capturedResultRow();
    assertThat(resultRow.messageType()).isEqualTo("UpdatePersonRejected");
    ResultPayload payload = codec.readValue(resultRow.payloadJson(), ResultPayload.class);
    assertThat(payload.error().code()).isEqualTo(RejectionCode.INVALID_MESSAGE.name());
  }

  @Test
  void retryOfARejectedIntentionReconstructsTheRejectedResultWithoutReinvokingTheHandler() {
    UUID personId = UUID.randomUUID();
    MessageEnvelope retry = envelope(personId, personId, IDEMPOTENCY_KEY);
    when(idempotency.claim(
            eq(PRODUCER), eq(IDEMPOTENCY_KEY), eq(MessageTypes.UPDATE_PERSON), eq(personId), any(), eq(retry.messageId()), any()))
        .thenReturn(
            new IdempotencyRecord(
                PRODUCER,
                IDEMPOTENCY_KEY,
                MessageTypes.UPDATE_PERSON,
                personId,
                fingerprintFor(retry),
                UUID.randomUUID(),
                IdempotencyStatus.REJECTED,
                null,
                RejectionCode.PERSON_VERSION_CONFLICT.name(),
                Instant.now(),
                Instant.now()));

    CommandOutcome outcome = dispatcher.dispatch(retry);

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.PERSON_VERSION_CONFLICT);
    verify(updateHandler, never()).handle(any());

    OutboxAppendRequest resultRow = capturedResultRow();
    assertThat(resultRow.messageType()).isEqualTo("UpdatePersonRejected");
    ResultPayload payload = codec.readValue(resultRow.payloadJson(), ResultPayload.class);
    assertThat(payload.status()).isEqualTo(ResultStatus.REJECTED);
    assertThat(payload.error().code()).isEqualTo(RejectionCode.PERSON_VERSION_CONFLICT.name());
  }

  @Test
  void malformedCreateEmailIsClaimedAndItsRejectionIsReconstructedWithoutReinvokingTheHandler() {
    UUID personId = UUID.randomUUID();
    MessageEnvelope malformed = createEnvelope(personId, "not-an-email", IDEMPOTENCY_KEY);
    when(createHandler.handle(any()))
        .thenThrow(new com.estoma.users.domain.PersonRejectedException(RejectionCode.INVALID_PERSON_DATA, "invalid email"));

    CommandOutcome firstOutcome = dispatcher.dispatch(malformed);

    assertThat(firstOutcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.INVALID_PERSON_DATA));
    ArgumentCaptor<String> fingerprintCaptor = ArgumentCaptor.forClass(String.class);
    verify(idempotency)
        .claim(
            eq(PRODUCER),
            eq(IDEMPOTENCY_KEY),
            eq(MessageTypes.CREATE_PERSON),
            eq(personId),
            fingerprintCaptor.capture(),
            eq(malformed.messageId()),
            any());
    String malformedFingerprint = fingerprintCaptor.getValue();
    verify(idempotency).markRejected(eq(PRODUCER), eq(IDEMPOTENCY_KEY), eq(RejectionCode.INVALID_PERSON_DATA.name()), any());

    MessageEnvelope retry = createEnvelope(personId, "not-an-email", IDEMPOTENCY_KEY);
    when(idempotency.claim(
            eq(PRODUCER),
            eq(IDEMPOTENCY_KEY),
            eq(MessageTypes.CREATE_PERSON),
            eq(personId),
            eq(malformedFingerprint),
            eq(retry.messageId()),
            any()))
        .thenReturn(
            new IdempotencyRecord(
                PRODUCER,
                IDEMPOTENCY_KEY,
                MessageTypes.CREATE_PERSON,
                personId,
                malformedFingerprint,
                malformed.messageId(),
                IdempotencyStatus.REJECTED,
                null,
                RejectionCode.INVALID_PERSON_DATA.name(),
                Instant.now(),
                Instant.now()));

    CommandOutcome retryOutcome = dispatcher.dispatch(retry);

    assertThat(retryOutcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.INVALID_PERSON_DATA));
    verify(createHandler, times(1)).handle(any());

    MessageEnvelope corrected = createEnvelope(personId, "ada@example.edu", IDEMPOTENCY_KEY);
    when(idempotency.claim(
            eq(PRODUCER),
            eq(IDEMPOTENCY_KEY),
            eq(MessageTypes.CREATE_PERSON),
            eq(personId),
            any(),
            eq(corrected.messageId()),
            any()))
        .thenReturn(
            new IdempotencyRecord(
                PRODUCER,
                IDEMPOTENCY_KEY,
                MessageTypes.CREATE_PERSON,
                personId,
                malformedFingerprint,
                malformed.messageId(),
                IdempotencyStatus.REJECTED,
                null,
                RejectionCode.INVALID_PERSON_DATA.name(),
                Instant.now(),
                Instant.now()));

    CommandOutcome correctedOutcome = dispatcher.dispatch(corrected);

    assertThat(correctedOutcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.INVALID_MESSAGE));
    verify(createHandler, times(1)).handle(any());
  }

  private HandlerResult succeededResult(UUID personId, int version) {
    return new HandlerResult(
        personId,
        version,
        new PersonUpdatedPayload(personId, "Ada", "Lovelace", null, "ada@example.edu", null, version, Instant.now()),
        MessageTypes.PERSON_UPDATED);
  }

  /** Mirrors PersonCommandDispatcher#buildRequestFingerprint's UPDATE_PERSON case, over one envelope()'s fixed payload. */
  private String fingerprintFor(MessageEnvelope envelope) {
    UpdatePersonCommand command = codec.convertPayload(envelope, UpdatePersonCommand.class);
    return RequestFingerprint.of(
        MessageTypes.UPDATE_PERSON,
        envelope.aggregateId().toString(),
        command.personId().toString(),
        String.valueOf(command.expectedVersion()),
        command.firstName(),
        command.paternalSurname(),
        command.maternalSurname(),
        command.institutionalEmail().trim().toLowerCase(java.util.Locale.ROOT),
        command.phone());
  }

  private MessageEnvelope createEnvelope(UUID personId, String institutionalEmail, String idempotencyKey) {
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, institutionalEmail, null);
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.CREATE_PERSON,
        1,
        MessageKind.COMMAND,
        PRODUCER,
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        idempotencyKey,
        personId,
        null,
        new Actor.Account(UUID.randomUUID()),
        codec.toJsonNode(command));
  }

  private OutboxAppendRequest capturedResultRow() {
    List<OutboxAppendRequest> resultRows = capturedResultRowsInOrder();
    assertThat(resultRows).hasSize(1);
    return resultRows.get(0);
  }

  private List<OutboxAppendRequest> capturedResultRowsInOrder() {
    ArgumentCaptor<OutboxAppendRequest> appended = ArgumentCaptor.forClass(OutboxAppendRequest.class);
    verify(outbox, org.mockito.Mockito.atLeastOnce()).append(appended.capture());
    return appended.getAllValues().stream().filter(row -> row.messageKind().equals(MessageKind.RESULT.name())).toList();
  }

  private MessageEnvelope envelope(UUID aggregateId, UUID payloadPersonId, String idempotencyKey) {
    UpdatePersonCommand command =
        new UpdatePersonCommand(payloadPersonId, 1, "Ada", "Lovelace", null, "ada@example.edu", null);
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.UPDATE_PERSON,
        1,
        MessageKind.COMMAND,
        PRODUCER,
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        idempotencyKey,
        aggregateId,
        null,
        new Actor.Account(UUID.randomUUID()),
        codec.toJsonNode(command));
  }
}
