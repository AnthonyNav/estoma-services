package com.estoma.appointments.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.appointments.application.port.out.PendingProjectionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ProjectionRecoveryHardeningTest {
  @Test
  void moreThanOneDeferredBatchDrainsWithoutFalseDegradedState() {
    PendingProjectionEventRepository pending = mock(PendingProjectionEventRepository.class);
    ProcessedMessageRepository inbox = mock(ProcessedMessageRepository.class);
    ProjectionEventDispatcher dispatcher = mock(ProjectionEventDispatcher.class);
    ProjectionRecoveryCoordinator recovery = mock(ProjectionRecoveryCoordinator.class);
    EnvelopeJsonCodec codec = new EnvelopeJsonCodec(new ObjectMapper());
    Clock clock = Clock.fixed(Instant.parse("2026-08-23T15:00:00Z"), ZoneOffset.UTC);

    List<PendingProjectionEventRepository.PendingProjectionEvent> first = new ArrayList<>();
    for (int i = 0; i < 50; i++) first.add(row(codec, i));
    List<PendingProjectionEventRepository.PendingProjectionEvent> second = List.of(row(codec, 50));
    when(pending.claim(eq("academic"), eq(50), anyString(), any(), any()))
        .thenReturn(first, second, List.of());
    when(inbox.wasProcessed(any())).thenReturn(true);

    PendingProjectionRecoveryWorker worker = new PendingProjectionRecoveryWorker(
        pending, inbox, dispatcher, recovery, codec, clock);
    worker.replay("academic");

    verify(dispatcher, times(51)).dispatch(any(MessageEnvelope.class));
    verify(recovery, never()).degraded(eq("academic"), anyString());
  }

  private static PendingProjectionEventRepository.PendingProjectionEvent row(
      EnvelopeJsonCodec codec, int index) {
    UUID source = UUID.randomUUID();
    var payload = new ObjectMapper().createObjectNode();
    payload.put("accountId", UUID.randomUUID().toString());
    payload.put("currentSemester", index + 1);
    MessageEnvelope envelope = new MessageEnvelope(source, "StudentSemesterChanged", 1,
        MessageKind.EVENT, "academic", Instant.parse("2026-08-23T15:00:00Z"), UUID.randomUUID(),
        null, null, null, UUID.randomUUID(), index + 1,
        new WireActor("SYSTEM", null, "academic"), payload);
    return new PendingProjectionEventRepository.PendingProjectionEvent(
        UUID.randomUUID(), source, "academic", envelope.messageType(), (long) index + 1,
        new String(codec.encode(envelope), java.nio.charset.StandardCharsets.UTF_8),
        "PENDING", 0, Instant.parse("2026-08-23T15:00:00Z"), null, null);
  }
}
