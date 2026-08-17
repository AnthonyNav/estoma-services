package com.estoma.academic.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.academic.application.AuthorizationProjectionRecoveryService;
import com.estoma.academic.application.StudentImportApplicationManager;
import com.estoma.academic.application.StudentImportPersonResolutionResults;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class StudentImportResultDispatcherTest {
  private static final Actor ACADEMIC = new Actor("SYSTEM", null, "academic");

  private final ObjectMapper mapper = new ObjectMapper();
  private final StudentImportPersonResolutionResults resolutions = mock(StudentImportPersonResolutionResults.class);
  private final StudentImportApplicationManager applications = mock(StudentImportApplicationManager.class);
  private final AuthorizationProjectionRecoveryService recovery = mock(AuthorizationProjectionRecoveryService.class);
  private final JdbcClient jdbc = mock(JdbcClient.class);
  private final StudentImportResultDispatcher dispatcher =
      new StudentImportResultDispatcher(resolutions, applications, recovery, jdbc);

  @Test
  void routesPersonResolutionToTheValidationWorkConsumer() {
    MessageEnvelope result = result("ResolvePersonByInstitutionalEmailResult", "users");
    when(resolutions.apply(result)).thenReturn(true);

    assertThat(dispatcher.dispatch(result)).isEqualTo(StudentImportResultDispatcher.Outcome.APPLIED);
    verify(resolutions).apply(result);
  }

  @Test
  void routesAuthorizationRecoveryToTheRecoveryConsumer() {
    MessageEnvelope result = result("GetAccountAuthorizationSnapshotResult", "authorization");
    when(recovery.applyResult(result)).thenReturn(AuthorizationProjectionRecoveryService.ResultOutcome.STALE);

    assertThat(dispatcher.dispatch(result)).isEqualTo(StudentImportResultDispatcher.Outcome.STALE);
    verify(recovery).applyResult(result);
  }

  @Test
  void routesOnlyKnownCommandResultsToTheImportProcess() {
    MessageEnvelope result = coordinatedResult("GrantSystemAccessResult", "authorization");
    when(applications.applyResult(result)).thenReturn(false);

    assertThat(dispatcher.dispatch(result)).isEqualTo(StudentImportResultDispatcher.Outcome.STALE);
    verify(applications).applyResult(result);
  }

  @Test
  void rejectsACommandResultWithoutImportCoordinates() {
    MessageEnvelope result = result("GrantSystemAccessResult", "authorization");
    assertThatThrownBy(() -> dispatcher.dispatch(result))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("coordinated Result requires payload.data");
  }

  @Test
  void rejectsAnUnknownResultInsteadOfAckingItAsAnImportResult() {
    MessageEnvelope result = result("UnrelatedResult", "authorization");
    assertThatThrownBy(() -> dispatcher.dispatch(result))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("unknown Academic Result UnrelatedResult");
  }

  @Test
  void rejectsACommandResultFromTheWrongOwnerBeforeInboxProcessing() {
    MessageEnvelope result = coordinatedResult("GrantSystemAccessResult", "users");
    assertThatThrownBy(() -> dispatcher.dispatch(result))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Result producer does not own GrantSystemAccessResult");
  }

  @Test
  void rejectsAResultThatDoesNotPreserveTheAcademicRequesterActor() {
    MessageEnvelope valid = result("GetAccountAuthorizationSnapshotResult", "authorization");
    MessageEnvelope wrongActor = new MessageEnvelope(
        valid.messageId(), valid.messageType(), valid.messageVersion(), valid.messageKind(), valid.producer(),
        valid.occurredAt(), valid.correlationId(), valid.causationId(), valid.operationId(), valid.idempotencyKey(),
        valid.aggregateId(), valid.aggregateVersion(), new Actor("SYSTEM", null, "authorization"), valid.payload());

    assertThatThrownBy(() -> dispatcher.dispatch(wrongActor))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("expected RESULT envelope for SYSTEM/academic requester");
  }

  @Test
  void rejectsAnUnsupportedResultVersionBeforeDispatchingIt() {
    MessageEnvelope result = new MessageEnvelope(UUID.randomUUID(), "GrantSystemAccessResult", 2, "RESULT",
        "authorization", Instant.now(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null,
        ACADEMIC, mapper.createObjectNode().put("status", "SUCCEEDED"));

    assertThatThrownBy(() -> dispatcher.dispatch(result))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("expected RESULT envelope for SYSTEM/academic requester");
  }

  private MessageEnvelope result(String type, String producer) {
    return new MessageEnvelope(UUID.randomUUID(), type, 1, "RESULT", producer, Instant.now(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, ACADEMIC,
        mapper.createObjectNode().put("status", "SUCCEEDED"));
  }

  private MessageEnvelope coordinatedResult(String type, String producer) {
    var payload = mapper.createObjectNode().put("status", "SUCCEEDED");
    payload.set("data", mapper.createObjectNode()
        .put("importId", UUID.randomUUID().toString())
        .put("planItemId", UUID.randomUUID().toString())
        .put("executionGeneration", 1));
    return new MessageEnvelope(UUID.randomUUID(), type, 1, "RESULT", producer, Instant.now(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "key", null, null, ACADEMIC, payload);
  }
}
