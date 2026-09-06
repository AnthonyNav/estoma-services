package com.estoma.platformbff.application.wash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort;
import com.estoma.platformbff.application.port.ReassignmentCandidatesPort.Refresh;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import com.estoma.platformbff.infrastructure.messaging.MessageEnvelopeCodec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
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
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class ReassignmentCandidatesResultServiceTest {
  private static final Instant NOW = Instant.parse("2026-09-05T18:00:00Z");
  private static final UUID EXECUTION_ID = UUID.randomUUID();
  @Mock ReassignmentCandidatesPort port;
  @Mock InboxRepository inbox;
  private ReassignmentCandidatesResultService service;
  private Refresh refresh;

  @BeforeEach
  void setUp() {
    service = new ReassignmentCandidatesResultService(port, inbox,
        Clock.fixed(NOW, ZoneOffset.UTC));
    refresh = new Refresh(EXECUTION_ID, 4, UUID.randomUUID(), UUID.randomUUID(), "PUBLISHED",
        null, null, 1, null, NOW.minusSeconds(1), null, NOW.minusSeconds(1));
  }

  @Test
  void appliesExactMatchingOwnerRankedSnapshot() {
    expectRefresh();
    when(inbox.recordIfNew(any())).thenReturn(true);
    IncomingResult result = success(candidate("107", "B"), List.of(candidate("107", "B"),
        candidate("108", "C")));

    assertThat(service.apply(result)).isTrue();

    var snapshot = ArgumentCaptor.forClass(ReassignmentCandidatesPort.Snapshot.class);
    verify(port).applySnapshot(org.mockito.ArgumentMatchers.eq(refresh),
        org.mockito.ArgumentMatchers.eq(result.messageId()), snapshot.capture(),
        org.mockito.ArgumentMatchers.eq(NOW));
    assertThat(snapshot.getValue().executionVersion()).isEqualTo(4);
    assertThat(snapshot.getValue().candidates()).hasSize(2);
  }

  @Test
  void acceptsOmittedRecommendationOnlyForProvenEmpty() {
    expectRefresh();
    when(inbox.recordIfNew(any())).thenReturn(true);

    assertThat(service.apply(success(null, List.of()))).isTrue();
    verify(port).applySnapshot(any(), any(), any(), any());
  }

  @Test
  void rejectsMismatchingVersionCorrelationAndRecommendationBeforeInbox() {
    expectRefresh();
    IncomingResult invalid = success(candidate("999", "Z"), List.of(candidate("107", "B")));

    assertThat(service.apply(invalid)).isFalse();
    verify(inbox, never()).recordIfNew(any());
    verify(port, never()).applySnapshot(any(), any(), any(), any());
  }

  @Test
  void rejectsNonCanonicalFreshnessAndUntrustedActor() {
    expectRefresh();
    IncomingResult valid = success(null, List.of());
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    data.put("freshUntil", NOW.plusSeconds(11).toString());
    IncomingResult invalidTtl = copy(valid, ActorContext.system("wash-execution"), data,
        Map.of("status", "SUCCEEDED", "data", data));
    assertThat(service.apply(invalidTtl)).isFalse();

    IncomingResult invalidActor = copy(valid, ActorContext.system("bff"), valid.data(), valid.payload());
    assertThat(service.apply(invalidActor)).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void acknowledgesTrustedNewerOwnerVersionWithoutApplyingItToLaggingProjection() {
    expectRefresh();
    when(inbox.recordIfNew(any())).thenReturn(true);
    IncomingResult valid = success(null, List.of());
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    data.put("executionVersion", 5);
    IncomingResult newer = copy(valid, valid.actor(), data,
        Map.of("status", "SUCCEEDED", "data", data));

    assertThat(service.apply(newer)).isTrue();
    verify(inbox).recordIfNew(any());
    verify(port, never()).applySnapshot(any(), any(), any(), any());
  }

  @Test
  void rejectsExplicitNullRecommendationOnTheNullFreeOwnerWire() {
    expectRefresh();
    IncomingResult valid = success(null, List.of());
    Map<String, Object> data = new LinkedHashMap<>(valid.data());
    data.put("recommendedCandidate", null);

    assertThat(service.apply(copy(valid, valid.actor(), data,
        Map.of("status", "SUCCEEDED", "data", data)))).isFalse();
    verify(inbox, never()).recordIfNew(any());
  }

  @Test
  void recordsCanonicalOwnerRejectionWithoutCreatingAnEmptySnapshot() {
    expectRefresh();
    when(inbox.recordIfNew(any())).thenReturn(true);
    String code = "REASSIGNMENT_NOT_PENDING";
    Map<String, Object> error = Map.of("code", code);
    IncomingResult rejected = new IncomingResult(UUID.randomUUID(),
        "GetReassignmentCandidatesResult", 1, MessageKind.RESULT, "wash-execution", NOW,
        refresh.correlationId(), refresh.messageId(), null, null, null, null,
        ActorContext.system("wash-execution"), "REJECTED", null, code, "{}", null,
        Map.of("status", "REJECTED", "error", error));

    assertThat(service.apply(rejected)).isTrue();
    verify(port).rejectRefresh(refresh, rejected.messageId(), code, NOW);
    verify(port, never()).applySnapshot(any(), any(), any(), any());
  }

  @Test
  void acceptsTheCanonicalRawWireEmptyResultWithOmittedNullFieldsAndRecommendation() {
    expectRefresh();
    when(inbox.recordIfNew(any())).thenReturn(true);
    String wire = """
        {
          "messageId":"%s",
          "messageType":"GetReassignmentCandidatesResult",
          "messageVersion":1,
          "messageKind":"RESULT",
          "producer":"wash-execution",
          "occurredAt":"%s",
          "correlationId":"%s",
          "causationId":"%s",
          "actor":{"type":"SYSTEM","service":"wash-execution"},
          "payload":{"status":"SUCCEEDED","data":{
            "washExecutionId":"%s",
            "executionVersion":4,
            "snapshotGeneratedAt":"%s",
            "freshUntil":"%s",
            "candidates":[]
          }}
        }
        """.formatted(UUID.randomUUID(), NOW, refresh.correlationId(), refresh.messageId(),
            EXECUTION_ID, NOW, NOW.plusSeconds(10));
    IncomingResult decoded = new MessageEnvelopeCodec(
        JsonMapper.builder().findAndAddModules().build())
        .readIncomingResult(wire.getBytes(StandardCharsets.UTF_8));

    assertThat(service.apply(decoded)).isTrue();
    verify(port).applySnapshot(any(), any(), any(), any());
  }

  private void expectRefresh() {
    when(port.findRefreshByMessageId(refresh.messageId())).thenReturn(Optional.of(refresh));
  }

  private IncomingResult success(Map<String, Object> recommendation,
      List<Map<String, Object>> values) {
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("washExecutionId", EXECUTION_ID.toString());
    data.put("executionVersion", 4);
    data.put("snapshotGeneratedAt", NOW.toString());
    data.put("freshUntil", NOW.plusSeconds(10).toString());
    if (recommendation != null) data.put("recommendedCandidate", recommendation);
    data.put("candidates", values);
    return new IncomingResult(UUID.randomUUID(), "GetReassignmentCandidatesResult", 1,
        MessageKind.RESULT, "wash-execution", NOW, refresh.correlationId(), refresh.messageId(),
        null, null, null, null, ActorContext.system("wash-execution"), "SUCCEEDED", "{}", null,
        null, data, Map.of("status", "SUCCEEDED", "data", data));
  }

  private static Map<String, Object> candidate(String cabin, String tank) {
    return Map.of("cabinId", UUID.nameUUIDFromBytes(cabin.getBytes()).toString(),
        "cabinCode", cabin, "cabinName", "Cabina " + cabin,
        "tankId", UUID.nameUUIDFromBytes(tank.getBytes()).toString(), "tankCode", tank,
        "tankName", "Tina " + tank, "availableCapacity", 2);
  }

  private static IncomingResult copy(IncomingResult source, ActorContext actor,
      Map<String, Object> data, Map<String, Object> payload) {
    return new IncomingResult(source.messageId(), source.messageType(), source.messageVersion(),
        source.messageKind(), source.producer(), source.occurredAt(), source.correlationId(),
        source.causationId(), source.operationId(), source.idempotencyKey(), source.aggregateId(),
        source.aggregateVersion(), actor, source.status(), source.dataJson(), source.errorCode(),
        source.errorDetailsJson(), data, payload);
  }
}
