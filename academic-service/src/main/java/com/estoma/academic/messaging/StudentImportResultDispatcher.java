package com.estoma.academic.messaging;

import com.estoma.academic.application.AuthorizationProjectionRecoveryService;
import com.estoma.academic.application.StudentImportApplicationManager;
import com.estoma.academic.application.StudentImportPersonResolutionResults;
import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

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
  private final JdbcClient jdbc;

  public StudentImportResultDispatcher(
      StudentImportPersonResolutionResults personResolutions,
      StudentImportApplicationManager applications,
      AuthorizationProjectionRecoveryService recovery,
      JdbcClient jdbc) {
    this.personResolutions = personResolutions;
    this.applications = applications;
    this.recovery = recovery;
    this.jdbc = jdbc;
  }

  @Transactional
  public Outcome dispatch(MessageEnvelope result) {
    validateEnvelope(result);

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
      ImportCoordinates coordinates = requireImportCoordinates(result.payload().path("data"));
      boolean applied = applications.applyResult(result);
      if (!applied) {
        return Outcome.STALE;
      }
      if ("SUCCEEDED".equals(result.payload().path("status").asString())
          && result.aggregateVersion() != null) {
        jdbc.sql("""
            UPDATE student_import_plan_items
            SET applied_aggregate_version=:version,updated_at=now()
            WHERE import_id=:import AND plan_item_id=:item
              AND execution_generation=:generation AND status='COMPLETED'
            """)
            .param("version", result.aggregateVersion())
            .param("import", coordinates.importId())
            .param("item", coordinates.planItemId())
            .param("generation", coordinates.executionGeneration())
            .update();
      }
      return Outcome.APPLIED;
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

  private static void validateEnvelope(MessageEnvelope result) {
    if (result == null || result.messageVersion() != 1 || result.messageId() == null
        || result.correlationId() == null || result.causationId() == null || result.operationId() == null
        || result.payload() == null || !result.payload().isObject()
        || !"RESULT".equals(result.messageKind())
        || !(result.actor() instanceof Actor actor)
        || !"SYSTEM".equals(actor.type())
        || actor.accountId() != null
        || !"academic".equals(actor.service())) {
      throw new IllegalArgumentException("expected RESULT envelope for SYSTEM/academic requester");
    }
  }

  private static ImportCoordinates requireImportCoordinates(JsonNode data) {
    if (!data.isObject()) {
      throw new IllegalArgumentException("coordinated Result requires payload.data");
    }
    UUID importId = uuid(data, "importId");
    UUID planItemId = uuid(data, "planItemId");
    int generation = data.path("executionGeneration").asInt(-1);
    if (importId == null || planItemId == null || generation < 1) {
      throw new IllegalArgumentException("invalid coordinated Result correlation data");
    }
    return new ImportCoordinates(importId, planItemId, generation);
  }

  private static UUID uuid(JsonNode data, String field) {
    if (!data.hasNonNull(field)) {
      return null;
    }
    try {
      return UUID.fromString(data.path(field).asString());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("invalid " + field, ex);
    }
  }

  private record ImportCoordinates(UUID importId, UUID planItemId, int executionGeneration) {}

  public enum Outcome { APPLIED, DUPLICATE, STALE }
}
