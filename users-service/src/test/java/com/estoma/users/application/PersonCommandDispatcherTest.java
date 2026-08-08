package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.users.application.command.UpdatePersonCommand;
import com.estoma.users.application.handler.HandlerResult;
import com.estoma.users.application.handler.PersonCommandHandler;
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
 * for {@link PersonCommandDispatcher}'s {@code aggregateId}/payload {@code
 * personId} consistency check (commands.md §2.1, §3). Deliberately not an
 * {@code *IT} test: the check is a pure function of the envelope and
 * payload, and exercising it through the full outbox/broker pipeline adds
 * shared-container risk (see git history) for no added coverage —
 * {@code CommandTransactionIT} and {@code PersonStatusHistoryTransactionIT}
 * already prove the real end-to-end persistence path for the commands
 * this dispatcher handles.
 */
class PersonCommandDispatcherTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());
  private final InboxPort inbox = mock(InboxPort.class);
  private final OutboxPort outbox = mock(OutboxPort.class);
  private final PersonCommandHandler updateHandler = mock(PersonCommandHandler.class);

  // Stubbed before the dispatcher field below is constructed: the handler
  // map is built once, in the constructor, from commandMessageType() --
  // stubbing it inside a @Test method would be too late.
  {
    when(updateHandler.commandMessageType()).thenReturn(MessageTypes.UPDATE_PERSON);
  }

  private final PersonCommandDispatcher dispatcher =
      new PersonCommandDispatcher(inbox, outbox, codec, List.of(updateHandler));

  @Test
  void matchingAggregateIdAndPersonIdInvokesTheHandler() {
    when(inbox.tryInsert(anyString(), any(), any())).thenReturn(true);
    UUID personId = UUID.randomUUID();
    HandlerResult handlerResult =
        new HandlerResult(
            personId,
            2,
            new PersonUpdatedPayload(personId, "Ada", "Lovelace", null, "ada@example.edu", null, 2, Instant.now()),
            MessageTypes.PERSON_UPDATED);
    when(updateHandler.handle(any())).thenReturn(handlerResult);

    CommandOutcome outcome = dispatcher.dispatch(envelope(personId, personId));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    verify(updateHandler).handle(any());

    ArgumentCaptor<OutboxAppendRequest> appended = ArgumentCaptor.forClass(OutboxAppendRequest.class);
    verify(outbox, org.mockito.Mockito.atLeastOnce()).append(appended.capture());
    OutboxAppendRequest resultRow =
        appended.getAllValues().stream()
            .filter(row -> row.messageKind().equals(MessageKind.RESULT.name()))
            .findFirst()
            .orElseThrow();
    ResultPayload payload = codec.readValue(resultRow.payloadJson(), ResultPayload.class);
    assertThat(payload.status()).isEqualTo(ResultStatus.SUCCEEDED);
    assertThat(payload.error()).isNull();
    SucceededPayload data =
        codec.readValue(codec.writeValueAsString(payload.data()), SucceededPayload.class);
    assertThat(data.personId()).isEqualTo(personId);
    assertThat(data.version()).isEqualTo(2);
  }

  @Test
  void mismatchedAggregateIdIsRejectedWithoutInvokingTheHandler() {
    when(inbox.tryInsert(anyString(), any(), any())).thenReturn(true);
    UUID personId = UUID.randomUUID();
    UUID unrelatedAggregateId = UUID.randomUUID();

    CommandOutcome outcome = dispatcher.dispatch(envelope(unrelatedAggregateId, personId));

    assertThat(outcome).isInstanceOf(CommandOutcome.Rejected.class);
    assertThat(((CommandOutcome.Rejected) outcome).code()).isEqualTo(RejectionCode.INVALID_MESSAGE);
    verify(updateHandler, never()).handle(any());

    ArgumentCaptor<OutboxAppendRequest> appended = ArgumentCaptor.forClass(OutboxAppendRequest.class);
    verify(outbox).append(appended.capture());
    OutboxAppendRequest resultRow = appended.getValue();
    assertThat(resultRow.messageKind()).isEqualTo(MessageKind.RESULT.name());
    assertThat(resultRow.messageType()).isEqualTo("UpdatePersonRejected");
    ResultPayload payload = codec.readValue(resultRow.payloadJson(), ResultPayload.class);
    assertThat(payload.status()).isEqualTo(ResultStatus.REJECTED);
    assertThat(payload.data()).isNull();
    assertThat(payload.error().code()).isEqualTo(RejectionCode.INVALID_MESSAGE.name());
  }

  @Test
  void absentAggregateIdDoesNotBlockTheHandler() {
    // Neither value being present is not itself an error -- only an
    // actual mismatch is rejected (commands.md §3).
    when(inbox.tryInsert(anyString(), any(), any())).thenReturn(true);
    UUID personId = UUID.randomUUID();
    HandlerResult handlerResult =
        new HandlerResult(
            personId,
            2,
            new PersonUpdatedPayload(personId, "Ada", "Lovelace", null, "ada@example.edu", null, 2, Instant.now()),
            MessageTypes.PERSON_UPDATED);
    when(updateHandler.handle(any())).thenReturn(handlerResult);

    CommandOutcome outcome = dispatcher.dispatch(envelope(null, personId));

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    verify(updateHandler).handle(any());
  }

  private MessageEnvelope envelope(UUID aggregateId, UUID payloadPersonId) {
    UpdatePersonCommand command =
        new UpdatePersonCommand(payloadPersonId, 1, "Ada", "Lovelace", null, "ada@example.edu", null);
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.UPDATE_PERSON,
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        aggregateId,
        null,
        new Actor.Account(UUID.randomUUID()),
        codec.toJsonNode(command));
  }
}
