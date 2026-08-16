package com.estoma.academic.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.academic.application.AuthorizationProjectionRecoveryService;
import com.estoma.academic.application.StudentImportApplicationManager;
import com.estoma.academic.application.StudentImportPersonResolutionResults;
import com.estoma.academic.contract.MessageEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class StudentImportResultDispatcherTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final StudentImportPersonResolutionResults resolutions = mock(StudentImportPersonResolutionResults.class);
  private final StudentImportApplicationManager applications = mock(StudentImportApplicationManager.class);
  private final AuthorizationProjectionRecoveryService recovery = mock(AuthorizationProjectionRecoveryService.class);
  private final StudentImportResultDispatcher dispatcher =
      new StudentImportResultDispatcher(resolutions, applications, recovery);

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
    MessageEnvelope result = result("GrantSystemAccessResult", "authorization");
    when(applications.applyResult(result)).thenReturn(false);

    assertThat(dispatcher.dispatch(result)).isEqualTo(StudentImportResultDispatcher.Outcome.STALE);
    verify(applications).applyResult(result);
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
    MessageEnvelope result = result("GrantSystemAccessResult", "users");
    assertThatThrownBy(() -> dispatcher.dispatch(result))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Result producer does not own GrantSystemAccessResult");
  }

  @Test
  void rejectsAnUnsupportedResultVersionBeforeDispatchingIt() {
    MessageEnvelope result = new MessageEnvelope(UUID.randomUUID(), "GrantSystemAccessResult", 2, "RESULT",
        "authorization", Instant.now(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null,
        null, mapper.createObjectNode().put("status", "SUCCEEDED"));

    assertThatThrownBy(() -> dispatcher.dispatch(result))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("expected RESULT envelope");
  }

  private MessageEnvelope result(String type, String producer) {
    return new MessageEnvelope(UUID.randomUUID(), type, 1, "RESULT", producer, Instant.now(),
        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null, null, null, null,
        mapper.createObjectNode().put("status", "SUCCEEDED"));
  }
}
