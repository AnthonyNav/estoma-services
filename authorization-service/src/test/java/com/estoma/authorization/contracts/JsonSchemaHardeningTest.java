package com.estoma.authorization.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonSchemaHardeningTest {

  private static final Path SCHEMAS = Path.of("contracts", "schemas");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonSchemaFactory FACTORY = schemaFactory();

  @Test
  void authorizationQueryFailedIsValidAndPageCursorMustBeUuid() {
    ObjectNode failed = envelope("GetAccountAuthorizationSnapshotResult", "RESULT", "authorization", "SYSTEM", "authorization");
    failed.put("causationId", UUID.randomUUID().toString());
    failed.put("operationId", UUID.randomUUID().toString());
    ObjectNode failedPayload = (ObjectNode) failed.get("payload");
    failedPayload.put("status", "FAILED");
    failedPayload.putObject("error").put("code", "TECHNICAL_FAILURE");
    assertValid("results/authorization-query-result.schema.json", failed);

    ObjectNode page = envelope("ListAccountAuthorizationSnapshotsPageResult", "RESULT", "authorization", "SYSTEM", "authorization");
    page.put("causationId", UUID.randomUUID().toString());
    page.put("operationId", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) page.get("payload");
    payload.put("status", "SUCCEEDED");
    ObjectNode data = payload.putObject("data");
    data.putArray("items");
    data.put("hasMore", true);
    data.put("nextCursor", "not-a-uuid");
    data.put("snapshotGeneratedAt", Instant.parse("2026-08-22T00:00:00Z").toString());
    assertThat(validate("results/authorization-query-result.schema.json", page)).isNotEmpty();
  }

  @Test
  void commandResultRequiresIdempotencyAndAcademicTerminalCorrelationData() {
    ObjectNode bff = envelope("AssignAccountRoleResult", "RESULT", "authorization", "ACCOUNT", null);
    ((ObjectNode) bff.get("actor")).put("accountId", UUID.randomUUID().toString());
    bff.put("causationId", UUID.randomUUID().toString());
    bff.put("operationId", UUID.randomUUID().toString());
    ObjectNode bffPayload = (ObjectNode) bff.get("payload");
    bffPayload.put("status", "REJECTED");
    bffPayload.putObject("error").put("code", "TARGET_SCOPE_NOT_ALLOWED");
    assertThat(validate("results/authorization-command-result.schema.json", bff)).isNotEmpty();

    bff.put("idempotencyKey", UUID.randomUUID().toString());
    assertValid("results/authorization-command-result.schema.json", bff);
    bffPayload.putObject("data")
        .put("importId", UUID.randomUUID().toString())
        .put("planItemId", UUID.randomUUID().toString())
        .put("executionGeneration", 1);
    assertThat(validate("results/authorization-command-result.schema.json", bff)).isNotEmpty();

    ObjectNode academic = envelope("AssignAccountRoleResult", "RESULT", "authorization", "SYSTEM", "academic");
    academic.put("causationId", UUID.randomUUID().toString());
    academic.put("operationId", UUID.randomUUID().toString());
    academic.put("idempotencyKey", UUID.randomUUID().toString());
    ObjectNode academicPayload = (ObjectNode) academic.get("payload");
    academicPayload.put("status", "FAILED");
    academicPayload.putObject("error").put("code", "TECHNICAL_FAILURE");
    assertThat(validate("results/authorization-command-result.schema.json", academic)).isNotEmpty();
    academicPayload.putObject("data")
        .put("importId", UUID.randomUUID().toString())
        .put("planItemId", UUID.randomUUID().toString())
        .put("executionGeneration", 1);
    assertValid("results/authorization-command-result.schema.json", academic);
  }

  @Test
  void causalAndAuthoritativeStatusesAreClosed() {
    ObjectNode command = envelope("AssignAccountRole", "COMMAND", "academic", "SYSTEM", "academic");
    command.put("operationId", UUID.randomUUID().toString());
    command.put("idempotencyKey", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) command.get("payload");
    payload.put("accountId", UUID.randomUUID().toString());
    payload.put("roleCode", "ALUMNO");
    ObjectNode execution = payload.putObject("importExecution");
    execution.put("importId", UUID.randomUUID().toString());
    execution.put("planItemId", UUID.randomUUID().toString());
    execution.put("executionGeneration", 1);
    execution.put("authorizedByAccountId", UUID.randomUUID().toString());
    execution.put("scope", "ASSIGN_STUDENT_ROLE");
    ObjectNode dependency = execution.putArray("causalDependencies").addObject();
    dependency.put("priorCommandType", "CreateAccount");
    dependency.put("priorResultMessageId", UUID.randomUUID().toString());
    dependency.put("priorResultStatus", "FAILED");
    dependency.put("expectedEventType", "AccountCreated");
    dependency.put("expectedAggregateId", UUID.randomUUID().toString());
    assertThat(validate("commands/authorization-command.schema.json", command)).isNotEmpty();

    dependency.put("priorResultStatus", "SUCCEEDED");
    ObjectNode evidence = execution.putArray("authoritativeEvidence").addObject();
    evidence.put("sourceService", "authentication");
    evidence.put("sourceOperation", "GetAccountStatusSnapshot");
    evidence.put("sourceResultStatus", "REJECTED");
    evidence.put("entityType", "Account");
    evidence.put("entityId", UUID.randomUUID().toString());
    assertThat(validate("commands/authorization-command.schema.json", command)).isNotEmpty();
    evidence.put("sourceResultStatus", "FOUND");
    assertValid("commands/authorization-command.schema.json", command);
  }

  private static ObjectNode envelope(
      String messageType, String messageKind, String producer, String actorType, String actorService) {
    ObjectNode message = JSON.createObjectNode();
    message.put("messageId", UUID.randomUUID().toString());
    message.put("messageType", messageType);
    message.put("messageVersion", 1);
    message.put("messageKind", messageKind);
    message.put("producer", producer);
    message.put("occurredAt", Instant.parse("2026-08-22T00:00:00Z").toString());
    message.put("correlationId", UUID.randomUUID().toString());
    ObjectNode actor = message.putObject("actor");
    actor.put("type", actorType);
    if (actorService != null) {
      actor.put("service", actorService);
    }
    message.putObject("payload");
    return message;
  }

  private static void assertValid(String schema, JsonNode message) {
    assertThat(validate(schema, message)).as(schema).isEmpty();
  }

  private static java.util.Set<com.networknt.schema.ValidationMessage> validate(String schema, JsonNode message) {
    var compiled = FACTORY.getSchema(SCHEMAS.resolve(schema).toUri());
    compiled.initializeValidators();
    return compiled.validate(message, execution -> execution.getExecutionConfig().setFormatAssertionsEnabled(true));
  }

  private static JsonSchemaFactory schemaFactory() {
    Map<String, String> documents = new HashMap<>();
    try (var paths = Files.walk(SCHEMAS)) {
      paths.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
        String relative = SCHEMAS.relativize(path).toString().replace('\\', '/');
        try {
          documents.put("https://estoma.com/contracts/authorization/v1/" + relative, Files.readString(path));
        } catch (IOException exception) {
          throw new IllegalStateException(exception);
        }
      });
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
    return JsonSchemaFactory.getInstance(
        VersionFlag.V202012, builder -> builder.schemaLoaders(loaders -> loaders.schemas(documents)));
  }
}
