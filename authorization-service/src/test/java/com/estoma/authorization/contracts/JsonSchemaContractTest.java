package com.estoma.authorization.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion.VersionFlag;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JsonSchemaContractTest {

  private static final Path CONTRACTS = Path.of("contracts");
  private static final Path SCHEMAS = CONTRACTS.resolve("schemas");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final JsonSchemaFactory FACTORY = schemaFactory();

  @Test
  void schemasAcceptImplementedV1MessageShapes() {
    assertValid("commands/authorization-command.schema.json", command());
    assertValid("results/authorization-command-result.schema.json", commandResult());
    assertValid("events/authorization-event.schema.json", authorizationEvent());
    assertValid("events/external-projection-event.schema.json", externalEvent());
    assertValid("queries/authorization-query.schema.json", authorizationQuery());
    assertValid("results/authorization-query-result.schema.json", authorizationQueryResult());
    assertValid("queries/recovery-query.schema.json", recoveryQuery());
    assertValid("results/recovery-result.schema.json", recoveryResult());
  }

  @Test
  void commandSchemaRejectsAnUnknownPayloadField() {
    ObjectNode command = command();
    ((ObjectNode) command.get("payload")).put("unexpected", true);

    assertThat(validate("commands/authorization-command.schema.json", command)).isNotEmpty();
  }

  @Test
  void externalEventSchemaAcceptsCanonicalOwnerOptionalShapes() {
    UUID personId = UUID.randomUUID();
    ObjectNode personCreated = envelope("PersonCreated", "EVENT", "users", "SYSTEM", null);
    personCreated.put("causationId", UUID.randomUUID().toString());
    personCreated.put("aggregateId", personId.toString());
    personCreated.put("aggregateVersion", 1);
    ObjectNode personPayload = (ObjectNode) personCreated.get("payload");
    personPayload.put("personId", personId.toString());
    personPayload.put("firstName", "Ada");
    personPayload.put("paternalSurname", "Lovelace");
    personPayload.put("institutionalEmail", "ada@estoma.mx");
    personPayload.put("status", "ACTIVE");
    personPayload.put("version", 1);
    personPayload.put("createdAt", Instant.parse("2026-08-16T00:00:00Z").toString());
    assertValid("events/external-projection-event.schema.json", personCreated);

    UUID accountId = UUID.randomUUID();
    ObjectNode accountDeactivated = envelope(
        "AccountDeactivated", "EVENT", "authentication", "SYSTEM", null);
    accountDeactivated.put("causationId", UUID.randomUUID().toString());
    accountDeactivated.put("aggregateId", accountId.toString());
    accountDeactivated.put("aggregateVersion", 2);
    ObjectNode accountPayload = (ObjectNode) accountDeactivated.get("payload");
    accountPayload.put("accountId", accountId.toString());
    accountPayload.put("personId", UUID.randomUUID().toString());
    accountPayload.put("previousStatus", "ACTIVE");
    accountPayload.put("status", "INACTIVE");
    accountPayload.put("reasonCode", "ADMINISTRATIVE");
    accountPayload.put("reasonDetail", "administrative closure");
    accountPayload.put("deactivatedAt", Instant.parse("2026-08-16T00:00:00Z").toString());
    assertValid("events/external-projection-event.schema.json", accountDeactivated);
  }

  @Test
  void academicCommandSchemaAcceptsEmptyArraysAndEvidenceWithoutResultMessageId() {
    ObjectNode message = envelope("AssignAccountRole", "COMMAND", "academic", "SYSTEM", null);
    message.put("operationId", UUID.randomUUID().toString());
    message.put("idempotencyKey", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) message.get("payload");
    payload.put("accountId", UUID.randomUUID().toString());
    payload.put("roleCode", "ALUMNO");
    ObjectNode execution = payload.putObject("importExecution");
    execution.put("importId", UUID.randomUUID().toString());
    execution.put("planItemId", UUID.randomUUID().toString());
    execution.put("executionGeneration", 1);
    execution.put("authorizedByAccountId", UUID.randomUUID().toString());
    execution.put("scope", "ASSIGN_STUDENT_ROLE");
    execution.putArray("causalDependencies");
    ObjectNode evidence = execution.putArray("authoritativeEvidence").addObject();
    evidence.put("sourceService", "authentication");
    evidence.put("sourceOperation", "CreateAccount");
    evidence.put("sourceResultStatus", "SUCCEEDED");
    evidence.put("entityType", "Account");
    evidence.put("entityId", UUID.randomUUID().toString());

    assertValid("commands/authorization-command.schema.json", message);
  }

  @Test
  void snapshotPageSchemaRejectsNonUuidCursor() {
    ObjectNode query = envelope(
        "ListAccountAuthorizationSnapshotsPage", "QUERY", "bff", "SYSTEM", null);
    query.put("operationId", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) query.get("payload");
    payload.put("cursor", "not-a-uuid");
    payload.put("limit", 100);

    assertThat(validate("queries/authorization-query.schema.json", query)).isNotEmpty();
  }

  @Test
  void asyncApiDocumentsEveryImplementedMessage() throws IOException {
    JsonNode document = new YAMLMapper().readTree(Files.readString(CONTRACTS.resolve("asyncapi.yaml")));

    assertThat(document.path("asyncapi").asText()).isEqualTo("3.0.0");
    assertThat(document.path("components").path("messages").size()).isEqualTo(41);
    assertThat(document.path("channels").has("commands.authorization")).isTrue();
    assertThat(document.path("channels").has("events.authorization")).isTrue();
    assertThat(document.path("channels").has("queries.authorization")).isTrue();
    assertThat(document.path("channels").has("results.authorization")).isTrue();
  }

  private static ObjectNode command() {
    ObjectNode message = envelope("AssignAccountRole", "COMMAND", "bff", "ACCOUNT", UUID.randomUUID());
    message.put("operationId", UUID.randomUUID().toString());
    message.put("idempotencyKey", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) message.get("payload");
    payload.put("accountId", UUID.randomUUID().toString());
    payload.put("roleCode", "ADMINISTRADOR_PRACTICAS");
    return message;
  }

  private static ObjectNode commandResult() {
    ObjectNode message = envelope("AssignAccountRoleResult", "RESULT", "authorization", "SYSTEM", null);
    message.put("causationId", UUID.randomUUID().toString());
    message.put("operationId", UUID.randomUUID().toString());
    message.put("idempotencyKey", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) message.get("payload");
    payload.put("status", "SUCCEEDED");
    ObjectNode data = payload.putObject("data");
    data.put("accountId", UUID.randomUUID().toString());
    data.put("roleCode", "ADMINISTRADOR_PRACTICAS");
    data.put("changed", true);
    data.put("newVersion", 1);
    return message;
  }

  private static ObjectNode authorizationEvent() {
    ObjectNode message = envelope("SystemAccessSuspended", "EVENT", "authorization", "SYSTEM", null);
    message.put("causationId", UUID.randomUUID().toString());
    message.put("aggregateId", UUID.randomUUID().toString());
    message.put("aggregateVersion", 1);
    ObjectNode payload = (ObjectNode) message.get("payload");
    payload.put("systemAccessId", UUID.randomUUID().toString());
    payload.put("accountId", UUID.randomUUID().toString());
    payload.put("systemId", UUID.randomUUID().toString());
    payload.put("systemCode", "LAVADO_ULTRASONICO");
    payload.put("previousStatus", "ACTIVE");
    payload.put("status", "SUSPENDED");
    payload.put("reasonCode", "ROLE_CHANGED");
    payload.put("suspendedAt", Instant.parse("2026-08-16T00:00:00Z").toString());
    return message;
  }

  private static ObjectNode externalEvent() {
    ObjectNode message = envelope(
        "StudentAcademicEligibilityChanged", "EVENT", "academic", "SYSTEM", null);
    message.put("causationId", UUID.randomUUID().toString());
    message.put("aggregateId", UUID.randomUUID().toString());
    message.put("aggregateVersion", 1);
    ObjectNode payload = (ObjectNode) message.get("payload");
    payload.put("eligibilityId", UUID.randomUUID().toString());
    payload.put("studentAcademicProfileId", UUID.randomUUID().toString());
    payload.put("accountId", UUID.randomUUID().toString());
    payload.put("systemCode", "PRACTICAS_PROFESIONALES");
    payload.put("eligible", true);
    payload.put("policyVersion", 1);
    payload.put("eligibilityVersion", 1);
    payload.put("academicProfileVersion", 1);
    payload.put("evaluatedAt", Instant.parse("2026-08-16T00:00:00Z").toString());
    return message;
  }

  private static ObjectNode authorizationQuery() {
    ObjectNode message = envelope(
        "GetAccountAuthorizationSnapshot", "QUERY", "bff", "SYSTEM", null);
    message.put("operationId", UUID.randomUUID().toString());
    ((ObjectNode) message.get("payload")).put("accountId", UUID.randomUUID().toString());
    return message;
  }

  private static ObjectNode authorizationQueryResult() {
    ObjectNode message = envelope(
        "GetAccountAuthorizationSnapshotResult", "RESULT", "authorization", "SYSTEM", null);
    message.put("causationId", UUID.randomUUID().toString());
    message.put("operationId", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) message.get("payload");
    payload.put("status", "FOUND");
    ObjectNode data = payload.putObject("data");
    data.put("accountId", UUID.randomUUID().toString());
    data.putArray("systemAccesses");
    data.put("snapshotGeneratedAt", Instant.parse("2026-08-16T00:00:00Z").toString());
    return message;
  }

  private static ObjectNode recoveryQuery() {
    ObjectNode message = envelope("GetPersonStatusSnapshot", "QUERY", "authorization", "SYSTEM", null);
    message.put("operationId", UUID.randomUUID().toString());
    ((ObjectNode) message.get("payload")).put("personId", UUID.randomUUID().toString());
    return message;
  }

  private static ObjectNode recoveryResult() {
    ObjectNode message = envelope("GetPersonStatusSnapshotResult", "RESULT", "users", "SYSTEM", null);
    message.put("causationId", UUID.randomUUID().toString());
    message.put("operationId", UUID.randomUUID().toString());
    ObjectNode payload = (ObjectNode) message.get("payload");
    payload.put("status", "FOUND");
    ObjectNode data = payload.putObject("data");
    data.put("personId", UUID.randomUUID().toString());
    data.put("status", "ACTIVE");
    data.put("sourceVersion", 1);
    data.put("updatedAt", Instant.parse("2026-08-16T00:00:00Z").toString());
    return message;
  }

  private static ObjectNode envelope(
      String messageType, String messageKind, String producer, String actorType, UUID accountId) {
    ObjectNode message = JSON.createObjectNode();
    message.put("messageId", UUID.randomUUID().toString());
    message.put("messageType", messageType);
    message.put("messageVersion", 1);
    message.put("messageKind", messageKind);
    message.put("producer", producer);
    message.put("occurredAt", Instant.parse("2026-08-16T00:00:00Z").toString());
    message.put("correlationId", UUID.randomUUID().toString());
    ObjectNode actor = message.putObject("actor");
    actor.put("type", actorType);
    if (accountId == null) {
      actor.put("service", producer);
    } else {
      actor.put("accountId", accountId.toString());
    }
    message.putObject("payload");
    return message;
  }

  private static void assertValid(String schema, JsonNode message) {
    assertThat(validate(schema, message)).as(schema).isEmpty();
  }

  private static Set<ValidationMessage> validate(String schema, JsonNode message) {
    JsonSchema compiled = FACTORY.getSchema(SCHEMAS.resolve(schema).toUri());
    compiled.initializeValidators();
    return compiled.validate(message, execution -> execution.getExecutionConfig().setFormatAssertionsEnabled(true));
  }

  private static JsonSchemaFactory schemaFactory() {
    Map<String, String> documents = new HashMap<>();
    try (var paths = Files.walk(SCHEMAS)) {
      paths.filter(path -> path.toString().endsWith(".json")).forEach(path -> {
        String relative = SCHEMAS.relativize(path).toString().replace('\\', '/');
        try {
          documents.put(
              "https://estoma.com/contracts/authorization/v1/" + relative,
              Files.readString(path));
        } catch (IOException exception) {
          throw new IllegalStateException("failed to load contract schema", exception);
        }
      });
    } catch (IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
    return JsonSchemaFactory.getInstance(
        VersionFlag.V202012, builder -> builder.schemaLoaders(loaders -> loaders.schemas(documents)));
  }
}
