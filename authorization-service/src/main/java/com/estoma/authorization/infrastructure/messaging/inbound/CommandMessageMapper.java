package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.command.AssignAccountRoleCommand;
import com.estoma.authorization.application.command.AuthorizationCommand;
import com.estoma.authorization.application.command.AuthoritativeEvidence;
import com.estoma.authorization.application.command.CausalDependency;
import com.estoma.authorization.application.command.ChangeAccountRoleCommand;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.GrantSystemAccessCommand;
import com.estoma.authorization.application.command.ImportExecution;
import com.estoma.authorization.application.command.RestoreSystemAccessCommand;
import com.estoma.authorization.application.command.RevokeSystemAccessCommand;
import com.estoma.authorization.application.command.SuspendSystemAccessCommand;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.domain.model.SystemAccessReasonCode;
import com.estoma.authorization.domain.model.SystemCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
final class CommandMessageMapper {

  private static final Set<String> ASSIGN_FIELDS = Set.of("accountId", "roleCode", "importExecution");
  private static final Set<String> CHANGE_FIELDS = Set.of("accountId", "newRoleCode", "expectedVersion");
  private static final Set<String> GRANT_FIELDS = Set.of("systemAccessId", "accountId", "systemCode", "importExecution");
  private static final Set<String> REASON_FIELDS = Set.of(
      "systemAccessId", "expectedVersion", "reasonCode", "reasonDetail");
  private static final Set<String> RESTORE_FIELDS = Set.of("systemAccessId", "expectedVersion");
  private static final Set<String> IMPORT_FIELDS = Set.of(
      "importId",
      "planItemId",
      "executionGeneration",
      "authorizedByAccountId",
      "scope",
      "causalDependency",
      "causalDependencies",
      "authoritativeEvidence");
  private static final Set<String> DEPENDENCY_FIELDS = Set.of(
      "priorCommandType",
      "priorResultMessageId",
      "priorResultStatus",
      "expectedEventType",
      "expectedAggregateId",
      "minimumSourceVersion");
  private static final Set<String> EVIDENCE_FIELDS = Set.of(
      "sourceService",
      "sourceOperation",
      "sourceResultStatus",
      "sourceResultMessageId",
      "entityType",
      "entityId",
      "evidenceVersion");

  MappedCommand map(MessageEnvelope envelope) {
    JsonNode payload = envelope.payload();
    AuthorizationCommand command = switch (envelope.messageType()) {
      case "AssignAccountRole" -> assign(envelope, payload);
      case "ChangeAccountRole" -> change(payload);
      case "GrantSystemAccess" -> grant(envelope, payload);
      case "SuspendSystemAccess" -> suspend(payload);
      case "RevokeSystemAccess" -> revoke(payload);
      case "RestoreSystemAccess" -> restore(payload);
      default -> throw new InvalidMessageException("unsupported command type");
    };
    return new MappedCommand(
        new CommandMetadata(
            envelope.messageId(),
            envelope.messageType(),
            envelope.messageVersion(),
            envelope.messageKind().name(),
            envelope.producer(),
            envelope.occurredAt(),
            envelope.correlationId(),
            envelope.causationId(),
            envelope.operationId(),
            envelope.idempotencyKey(),
            WireValues.actor(envelope.actor()),
            envelope.aggregateId(),
            envelope.aggregateVersion()),
        command);
  }

  private static AuthorizationCommand assign(MessageEnvelope envelope, JsonNode payload) {
    WireValues.fields(payload, Set.of("accountId", "roleCode"), ASSIGN_FIELDS);
    return new AssignAccountRoleCommand(
        WireValues.uuid(payload, "accountId"),
        WireValues.enumValue(payload, "roleCode", RoleCode.class),
        importExecution(envelope.producer(), payload, "ASSIGN_STUDENT_ROLE"));
  }

  private static AuthorizationCommand change(JsonNode payload) {
    WireValues.fields(payload, CHANGE_FIELDS, CHANGE_FIELDS);
    return new ChangeAccountRoleCommand(
        WireValues.uuid(payload, "accountId"),
        WireValues.enumValue(payload, "newRoleCode", RoleCode.class),
        WireValues.positiveInt(payload, "expectedVersion"));
  }

  private static AuthorizationCommand grant(MessageEnvelope envelope, JsonNode payload) {
    WireValues.fields(payload, Set.of("systemAccessId", "accountId", "systemCode"), GRANT_FIELDS);
    return new GrantSystemAccessCommand(
        WireValues.uuid(payload, "systemAccessId"),
        WireValues.uuid(payload, "accountId"),
        WireValues.enumValue(payload, "systemCode", SystemCode.class),
        importExecution(envelope.producer(), payload, "GRANT_STUDENT_SYSTEM_ACCESS"));
  }

  private static AuthorizationCommand suspend(JsonNode payload) {
    WireValues.fields(payload, Set.of("systemAccessId", "expectedVersion", "reasonCode"), REASON_FIELDS);
    return new SuspendSystemAccessCommand(
        WireValues.uuid(payload, "systemAccessId"),
        WireValues.positiveInt(payload, "expectedVersion"),
        reason(payload),
        reasonDetail(payload));
  }

  private static AuthorizationCommand revoke(JsonNode payload) {
    WireValues.fields(payload, Set.of("systemAccessId", "expectedVersion", "reasonCode"), REASON_FIELDS);
    return new RevokeSystemAccessCommand(
        WireValues.uuid(payload, "systemAccessId"),
        WireValues.positiveInt(payload, "expectedVersion"),
        reason(payload),
        reasonDetail(payload));
  }

  private static AuthorizationCommand restore(JsonNode payload) {
    WireValues.fields(payload, RESTORE_FIELDS, RESTORE_FIELDS);
    return new RestoreSystemAccessCommand(
        WireValues.uuid(payload, "systemAccessId"), WireValues.positiveInt(payload, "expectedVersion"));
  }

  private static SystemAccessReasonCode reason(JsonNode payload) {
    SystemAccessReasonCode reason = WireValues.enumValue(payload, "reasonCode", SystemAccessReasonCode.class);
    if (reason == SystemAccessReasonCode.ROLE_CHANGED) {
      throw new InvalidMessageException("ROLE_CHANGED is not valid for a public Command");
    }
    return reason;
  }

  private static String reasonDetail(JsonNode payload) {
    SystemAccessReasonCode reason = WireValues.enumValue(payload, "reasonCode", SystemAccessReasonCode.class);
    String detail = WireValues.optionalText(payload, "reasonDetail");
    if (reason == SystemAccessReasonCode.OTHER && detail == null) {
      throw new InvalidMessageException("reasonDetail is required for OTHER");
    }
    if (reason != SystemAccessReasonCode.OTHER && detail != null) {
      throw new InvalidMessageException("reasonDetail is only valid for OTHER");
    }
    return detail;
  }

  private static ImportExecution importExecution(
      String producer, JsonNode payload, String requiredScope) {
    JsonNode value = WireValues.optionalObject(payload, "importExecution");
    if ("academic".equals(producer) && value == null) {
      throw new InvalidMessageException("academic Command requires importExecution");
    }
    if (!"academic".equals(producer) && value != null) {
      throw new InvalidMessageException("importExecution is only valid for academic Commands");
    }
    if (value == null) {
      return null;
    }
    WireValues.fields(
        value,
        Set.of("importId", "planItemId", "executionGeneration", "authorizedByAccountId", "scope"),
        IMPORT_FIELDS);
    String scope = WireValues.text(value, "scope");
    if (!requiredScope.equals(scope)) {
      throw new InvalidMessageException("importExecution scope is incompatible with Command");
    }
    if (value.has("causalDependency") && value.has("causalDependencies")) {
      throw new InvalidMessageException("only one causal dependency representation is allowed");
    }
    List<CausalDependency> dependencies = new ArrayList<>();
    if (value.has("causalDependency")) {
      dependencies.add(dependency(WireValues.object(value.get("causalDependency"), "causalDependency")));
    }
    if (value.has("causalDependencies")) {
      for (JsonNode item : WireValues.array(value, "causalDependencies")) {
        dependencies.add(dependency(WireValues.object(item, "causalDependencies item")));
      }
    }
    List<AuthoritativeEvidence> evidence = new ArrayList<>();
    if (value.has("authoritativeEvidence")) {
      for (JsonNode item : WireValues.array(value, "authoritativeEvidence")) {
        evidence.add(evidence(WireValues.object(item, "authoritativeEvidence item")));
      }
    }
    return new ImportExecution(
        WireValues.uuid(value, "importId"),
        WireValues.uuid(value, "planItemId"),
        WireValues.positiveInt(value, "executionGeneration"),
        WireValues.uuid(value, "authorizedByAccountId"),
        scope,
        dependencies,
        evidence);
  }

  private static CausalDependency dependency(JsonNode value) {
    WireValues.fields(
        value,
        Set.of(
            "priorCommandType",
            "priorResultMessageId",
            "priorResultStatus",
            "expectedEventType",
            "expectedAggregateId"),
        DEPENDENCY_FIELDS);
    return new CausalDependency(
        WireValues.text(value, "priorCommandType"),
        WireValues.uuid(value, "priorResultMessageId"),
        WireValues.text(value, "priorResultStatus"),
        WireValues.text(value, "expectedEventType"),
        WireValues.uuid(value, "expectedAggregateId"),
        WireValues.optionalPositiveInt(value, "minimumSourceVersion"));
  }

  private static AuthoritativeEvidence evidence(JsonNode value) {
    WireValues.fields(
        value,
        Set.of(
            "sourceService",
            "sourceOperation",
            "sourceResultStatus",
            "entityType",
            "entityId"),
        EVIDENCE_FIELDS);
    return new AuthoritativeEvidence(
        WireValues.text(value, "sourceService"),
        WireValues.text(value, "sourceOperation"),
        WireValues.text(value, "sourceResultStatus"),
        WireValues.optionalUuid(value, "sourceResultMessageId"),
        WireValues.text(value, "entityType"),
        WireValues.uuid(value, "entityId"),
        WireValues.optionalPositiveInt(value, "evidenceVersion"));
  }

  record MappedCommand(CommandMetadata metadata, AuthorizationCommand command) {}
}
