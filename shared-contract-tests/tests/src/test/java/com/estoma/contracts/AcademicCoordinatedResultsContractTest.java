package com.estoma.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.academic.application.AuthorizationProjectionRecoveryService;
import com.estoma.academic.application.StudentImportApplicationManager;
import com.estoma.academic.application.StudentImportPersonResolutionResults;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.messaging.StudentImportResultDispatcher;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

class AcademicCoordinatedResultsContractTest {

  private final StudentImportPersonResolutionResults resolutions = mock(StudentImportPersonResolutionResults.class);
  private final StudentImportApplicationManager applications = mock(StudentImportApplicationManager.class);
  private final AuthorizationProjectionRecoveryService recovery = mock(AuthorizationProjectionRecoveryService.class);
  private final JdbcClient jdbc = mock(JdbcClient.class);
  private final StudentImportResultDispatcher dispatcher =
      new StudentImportResultDispatcher(resolutions, applications, recovery, jdbc);
  private final ObjectMapper mapper = new ObjectMapper();

  @ParameterizedTest
  @MethodSource("coordinatedCommandResults")
  void academicAcceptsTerminalOwnerResultsWithGenerationFencing(String resultType, String owner) {
    UUID importId = UUID.randomUUID();
    UUID planItemId = UUID.randomUUID();
    int generation = 3;
    var payload = mapper.valueToTree(Map.of(
        "status", "FAILED",
        "data", Map.of(
            "importId", importId,
            "planItemId", planItemId,
            "executionGeneration", generation),
        "error", Map.of("code", "TECHNICAL_FAILURE")));
    MessageEnvelope result = new MessageEnvelope(
        UUID.randomUUID(), resultType, 1, "RESULT", owner, Instant.now(), UUID.randomUUID(),
        UUID.randomUUID(), UUID.randomUUID(), "student-import:" + importId + ":" + planItemId + ":" + generation,
        null, null, new Actor("SYSTEM", null, "academic"), payload);
    when(applications.applyResult(any())).thenReturn(true);

    assertThat(dispatcher.dispatch(result)).isEqualTo(StudentImportResultDispatcher.Outcome.APPLIED);
    verify(applications).applyResult(result);
  }

  private static Stream<Arguments> coordinatedCommandResults() {
    return Stream.of(
        Arguments.of("CreatePersonResult", "users"),
        Arguments.of("UpdatePersonResult", "users"),
        Arguments.of("CreateAccountResult", "authentication"),
        Arguments.of("ActivateAccountResult", "authentication"),
        Arguments.of("DeactivateAccountResult", "authentication"),
        Arguments.of("AssignAccountRoleResult", "authorization"),
        Arguments.of("GrantSystemAccessResult", "authorization"));
  }
}
