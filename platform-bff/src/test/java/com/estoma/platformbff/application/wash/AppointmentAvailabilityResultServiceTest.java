package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Context;
import com.estoma.platformbff.application.port.AppointmentAvailabilityPort.Refresh;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class AppointmentAvailabilityResultServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-04T18:00:00Z");
  private static final Context CONTEXT = new Context(UUID.randomUUID(), LocalDate.of(2026, 9, 4),
      "NORMAL", 15, "HIGH_SPEED", UUID.randomUUID());

  @Mock AppointmentAvailabilityPort availability;
  @Mock InboxRepository inbox;
  private AppointmentAvailabilityResultService service;
  private Refresh refresh;

  @BeforeEach
  void setUp() {
    service = new AppointmentAvailabilityResultService(availability, inbox,
        Clock.fixed(NOW, ZoneOffset.UTC));
    refresh = Refresh.pending(CONTEXT, NOW.minusSeconds(1));
  }

  @Test
  void appliesTheExactOwnerSnapshotWithCorrelationAndFreshnessFences() {
    expectRefresh();
    when(inbox.recordIfNew(any())).thenReturn(true);
    UUID resultId = UUID.randomUUID();
    IncomingResult result = success(resultId, CONTEXT);

    assertThat(service.apply(result)).isTrue();

    var snapshot = ArgumentCaptor.forClass(AppointmentAvailabilityPort.Snapshot.class);
    verify(availability).applySnapshot(org.mockito.ArgumentMatchers.eq(refresh),
        org.mockito.ArgumentMatchers.eq(resultId), snapshot.capture(),
        org.mockito.ArgumentMatchers.eq(NOW));
    assertThat(snapshot.getValue().requestContext()).isEqualTo(CONTEXT);
    assertThat(snapshot.getValue().availableTimeSlots()).singleElement()
        .satisfies(slot -> assertThat(slot.availableCapacity()).isEqualTo(4));
  }

  @Test
  void rejectsAnEchoForAnotherStudentBeforeInboxMutation() {
    expectRefresh();
    Context other = new Context(UUID.randomUUID(), CONTEXT.serviceDate(), CONTEXT.appointmentType(),
        CONTEXT.instrumentCount(), CONTEXT.pieceType(), CONTEXT.courseSectionId());

    assertThat(service.apply(success(UUID.randomUUID(), other))).isFalse();

    verify(inbox, never()).recordIfNew(any());
    verify(availability, never()).applySnapshot(any(), any(), any(), any());
  }

  @Test
  void rejectsUnknownDataFieldsFailClosed() {
    expectRefresh();
    IncomingResult valid = success(UUID.randomUUID(), CONTEXT);
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    data.put("unregistered", true);
    Map<String, Object> payload = Map.of("status", "SUCCEEDED", "data", data);
    IncomingResult invalid = copy(valid, data, payload);

    assertThat(service.apply(invalid)).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void duplicateResultIsAcknowledgedWithoutApplyingTheSnapshotAgain() {
    expectRefresh();
    when(inbox.recordIfNew(any())).thenReturn(false);

    assertThat(service.apply(success(UUID.randomUUID(), CONTEXT))).isTrue();

    verify(availability, never()).applySnapshot(any(), any(), any(), any());
  }

  @Test
  void rejectsAResultFromAnUntrustedSystemActorBeforeLookingUpTheRefresh() {
    IncomingResult valid = success(UUID.randomUUID(), CONTEXT);
    IncomingResult invalid = new IncomingResult(valid.messageId(), valid.messageType(),
        valid.messageVersion(), valid.messageKind(), valid.producer(), valid.occurredAt(),
        valid.correlationId(), valid.causationId(), valid.operationId(), valid.idempotencyKey(),
        valid.aggregateId(), valid.aggregateVersion(), ActorContext.system("bff"), valid.status(),
        valid.dataJson(), valid.errorCode(), valid.errorDetailsJson(), valid.data(), valid.payload());

    assertThat(service.apply(invalid)).isFalse();

    verify(availability, never()).findRefreshByMessageId(any());
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void rejectsInconsistentExceptionalAuthorizationFlags() {
    expectRefresh();
    IncomingResult valid = success(UUID.randomUUID(), CONTEXT);
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    data.put("exceptionalAuthorizationRequired", false);
    data.put("exceptionalAuthorizationAvailable", true);
    data.put("exceptionalAuthorizationId", UUID.randomUUID().toString());

    assertThat(service.apply(copy(valid, data,
        Map.of("status", "SUCCEEDED", "data", data)))).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void rejectsRequiredUnavailableAuthorizationWithoutItsCanonicalBlocker() {
    expectRefresh();
    IncomingResult valid = success(UUID.randomUUID(), CONTEXT);
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    data.put("exceptionalAuthorizationRequired", true);

    assertThat(service.apply(copy(valid, data,
        Map.of("status", "SUCCEEDED", "data", data)))).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void rejectsSlotWhoseBookingDeadlineIsAfterItsStart() {
    expectRefresh();
    IncomingResult valid = success(UUID.randomUUID(), CONTEXT);
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    Map<String, Object> slot = new LinkedHashMap<>((Map<String, Object>)
        ((List<?>) data.get("availableTimeSlots")).getFirst());
    slot.put("bookingDeadlineAt", NOW.plusSeconds(301).toString());
    data.put("availableTimeSlots", List.of(slot));

    assertThat(service.apply(copy(valid, data,
        Map.of("status", "SUCCEEDED", "data", data)))).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void acceptsEveryCanonicalBlockerInOwnerOrder() {
    expectRefresh();
    when(inbox.recordIfNew(any())).thenReturn(true);
    IncomingResult valid = success(UUID.randomUUID(), CONTEXT);
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    data.put("canSchedule", false);
    data.put("blockingReasons", List.of("ACTIVE_APPOINTMENT_EXISTS", "STUDENT_BLOCKED",
        "DAILY_COMPLETED_LIMIT", "EXCEPTIONAL_AUTHORIZATION_REQUIRED",
        "ACADEMIC_ENROLLMENT_INVALID", "STUDENT_ACCESS_DENIED"));
    data.put("exceptionalAuthorizationRequired", true);
    data.put("availableTimeSlots", List.of());

    assertThat(service.apply(copy(valid, data,
        Map.of("status", "SUCCEEDED", "data", data)))).isTrue();
    verify(availability).applySnapshot(any(), any(), any(), any());
  }

  @Test
  void rejectsCanonicalBlockersOutOfOrder() {
    expectRefresh();
    IncomingResult valid = success(UUID.randomUUID(), CONTEXT);
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    data.put("canSchedule", false);
    data.put("blockingReasons", List.of("STUDENT_ACCESS_DENIED", "STUDENT_BLOCKED"));

    assertThat(service.apply(copy(valid, data,
        Map.of("status", "SUCCEEDED", "data", data)))).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  private void expectRefresh() {
    when(availability.findRefreshByMessageId(refresh.messageId())).thenReturn(Optional.of(refresh));
  }

  private IncomingResult success(UUID resultId, Context context) {
    Map<String, Object> request = Map.of(
        "studentAccountId", context.studentAccountId().toString(),
        "serviceDate", context.serviceDate().toString(),
        "appointmentType", context.appointmentType(),
        "instrumentCount", context.instrumentCount(),
        "pieceType", context.pieceType(),
        "courseSectionId", context.courseSectionId().toString());
    Map<String, Object> slot = Map.of(
        "appointmentTimeSlotId", UUID.randomUUID().toString(),
        "startsAt", NOW.plusSeconds(300).toString(),
        "endsAt", NOW.plusSeconds(3600).toString(),
        "availableCapacity", 4,
        "bookingDeadlineAt", NOW.plusSeconds(30).toString());
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("snapshotId", resultId.toString());
    data.put("snapshotGeneratedAt", NOW.toString());
    data.put("freshUntil", NOW.plusSeconds(30).toString());
    data.put("requestContext", request);
    data.put("canSchedule", true);
    data.put("blockingReasons", List.of());
    data.put("dailyPenaltyPoints", 0);
    data.put("dailyCompletedAppointments", 0);
    data.put("exceptionalAuthorizationRequired", false);
    data.put("exceptionalAuthorizationAvailable", false);
    data.put("exceptionalAuthorizationId", null);
    data.put("availableTimeSlots", List.of(slot));
    Map<String, Object> payload = Map.of("status", "SUCCEEDED", "data", data);
    return new IncomingResult(resultId, "GetAppointmentAvailabilityResult", 1,
        MessageKind.RESULT, "appointments", NOW, refresh.correlationId(), refresh.messageId(),
        null, null, null, null, ActorContext.system("appointments"), "SUCCEEDED", "{}", null,
        null, data, payload);
  }

  private static IncomingResult copy(IncomingResult source, Map<String, Object> data,
      Map<String, Object> payload) {
    return new IncomingResult(source.messageId(), source.messageType(), source.messageVersion(),
        source.messageKind(), source.producer(), source.occurredAt(), source.correlationId(),
        source.causationId(), source.operationId(), source.idempotencyKey(), source.aggregateId(),
        source.aggregateVersion(), source.actor(), source.status(), source.dataJson(),
        source.errorCode(), source.errorDetailsJson(), data, payload);
  }
}
