package com.estoma.academic.messaging;

import com.estoma.academic.application.AuthorizationProjectionRecoveryService;
import com.estoma.academic.application.StudentImportApplicationManager;
import com.estoma.academic.application.StudentImportPersonResolutionResults;
import com.estoma.academic.contract.MessageEnvelope;
import java.util.Set;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Routes each Result to its owning durable consumer instead of inferring from payload shape. */
@Component
public class StudentImportResultDispatcher {
  private static final String PERSON_RESOLUTION = "ResolvePersonByInstitutionalEmailResult";
  private static final Set<String> IMPORT_COMMAND_RESULTS = Set.of(
      "CreatePersonResult", "UpdatePersonResult", "CreateAccountResult",
      "ActivateAccountResult", "DeactivateAccountResult", "AssignAccountRoleResult",
      "GrantSystemAccessResult");
  private static final Set<String> AUTHORIZATION_RECOVERY_RESULTS = Set.of(
      "GetAccountAuthorizationSnapshotResult",
      "ListAccountAuthorizationSnapshotsPageResult");
  private static final Map<String, String> COMMAND_RESULT_PRODUCERS = Map.of(
      "CreatePersonResult", "users",
      "UpdatePersonResult", "users",
      "CreateAccountResult", "authentication",
      "ActivateAccountResult", "authentication",
      "DeactivateAccountResult", "authentication",
      "AssignAccountRoleResult", "authorization",
      "GrantSystemAccessResult", "authorization");

  private final StudentImportPersonResolutionResults personResolutions;
  private final StudentImportApplicationManager applications;
  private final AuthorizationProjectionRecoveryService recovery;

  public StudentImportResultDispatcher(
      StudentImportPersonResolutionResults personResolutions,
      StudentImportApplicationManager applications,
      AuthorizationProjectionRecoveryService recovery) {
    this.personResolutions = personResolutions;
    this.applications = applications;
    this.recovery = recovery;
  }

  public Outcome dispatch(MessageEnvelope result) {
     if (result == null || result.messageVersion() != 1 || result.messageId() == null || result.correlationId() == null
        || result.causationId() == null || result.operationId() == null
        || result.payload() == null || !result.payload().isObject()
        || !"RESULT".equals(result.messageKind())) {
      throw new IllegalArgumentException("expected RESULT envelope");
    }
    if (PERSON_RESOLUTION.equals(result.messageType())) {
      if (!"users".equals(result.producer())) {
        throw new IllegalArgumentException("person resolution Result producer is not users");
      }
      return personResolutions.apply(result) ? Outcome.APPLIED : Outcome.DUPLICATE;
    }
    if (IMPORT_COMMAND_RESULTS.contains(result.messageType())) {
      if (!COMMAND_RESULT_PRODUCERS.get(result.messageType()).equals(result.producer())) {
        throw new IllegalArgumentException("Result producer does not own " + result.messageType());
      }
      return applications.applyResult(result) ? Outcome.APPLIED : Outcome.STALE;
    }
    if (AUTHORIZATION_RECOVERY_RESULTS.contains(result.messageType())) {
      if (!"authorization".equals(result.producer())) {
        throw new IllegalArgumentException("Result producer does not own " + result.messageType());
      }
      return switch (recovery.applyResult(result)) {
        case APPLIED -> Outcome.APPLIED;
        case DUPLICATE -> Outcome.DUPLICATE;
        case STALE -> Outcome.STALE;
      };
    }
    throw new IllegalArgumentException("unknown Academic Result " + result.messageType());
  }

  public enum Outcome { APPLIED, DUPLICATE, STALE }
}
