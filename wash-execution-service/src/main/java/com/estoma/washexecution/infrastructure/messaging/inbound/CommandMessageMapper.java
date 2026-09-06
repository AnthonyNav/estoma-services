package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.command.CommandMetadata;
import com.estoma.washexecution.application.command.CommandRequest;
import com.estoma.washexecution.application.command.WashExecutionCommand;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class CommandMessageMapper {
  private static final Set<String> REASSIGNMENT_CANONICAL_ALIASES = Set.of(
      "selectedCabinId", "selectedTankId", "assignedByAccountId", "assignedAt");
  private static final Set<String> REASSIGNMENT_LEGACY_ALIASES = Set.of(
      "cabinId", "tankId", "resolvedByAccountId", "resolvedAt");
  private final EnvelopeJsonCodec codec;

  public CommandMessageMapper(EnvelopeJsonCodec codec) {
    this.codec = codec;
  }

  public CommandRequest map(MessageEnvelope e) {
    validatePayload(e.messageType(), e.payload());
    var actor = e.actor();
    CommandMetadata metadata = new CommandMetadata(
        e.messageId(), e.correlationId(), e.causationId(), e.operationId(), e.producer(), e.idempotencyKey(),
        actor == null ? null : new ActorContext(actor.type(), actor.accountId(), actor.service()));
    return new CommandRequest(metadata, command(e));
  }

  private WashExecutionCommand command(MessageEnvelope e) {
    return switch (e.messageType()) {
      case "RegisterWashArrival" -> codec.convertPayload(e, WashExecutionCommand.RegisterWashArrival.class);
      case "DecideWashEntry" -> codec.convertPayload(e, WashExecutionCommand.DecideWashEntry.class);
      case "ResolvePendingReassignment" -> resolvePendingReassignment(e.payload());
      case "DisableOperationalResource" -> codec.convertPayload(e, WashExecutionCommand.DisableOperationalResource.class);
      case "RestoreOperationalResource" -> codec.convertPayload(e, WashExecutionCommand.RestoreOperationalResource.class);
      case "SubmitWashExit" -> codec.convertPayload(e, WashExecutionCommand.SubmitWashExit.class);
      case "CompleteWashExecution" -> codec.convertPayload(e, WashExecutionCommand.CompleteWashExecution.class);
      case "CancelWashExecutionByClinic" -> codec.convertPayload(e, WashExecutionCommand.CancelWashExecutionByClinic.class);
      default -> throw invalid("UNSUPPORTED_COMMAND");
    };
  }

  private static void validatePayload(String type, JsonNode payload) {
    if (payload == null || !payload.isObject()) {
      throw invalid("INVALID_COMMAND_PAYLOAD");
    }
    switch (type) {
      case "RegisterWashArrival" -> require(payload,
          "washExecutionId", "appointmentId", "arrivedAt");
      case "DecideWashEntry" -> {
        require(payload, "washExecutionId", "identityConfirmed", "requirementsSatisfied", "decision",
            "decidedByAccountId", "decidedAt", "expectedVersion");
        requireBoolean(payload, "identityConfirmed");
        requireBoolean(payload, "requirementsSatisfied");
        requireIntegral(payload, "expectedVersion");
      }
      case "ResolvePendingReassignment" -> {
        requireUnambiguousReassignmentShape(payload);
        requireIntegral(payload, "expectedVersion");
      }
      case "DisableOperationalResource" -> require(payload,
          "resourceUnavailabilityId", "causeType", "reason", "disabledByAccountId", "disabledAt");
      case "RestoreOperationalResource" -> {
        require(payload, "resourceUnavailabilityId", "restoredByAccountId", "restoredAt", "expectedVersion");
        requireIntegral(payload, "expectedVersion");
      }
      case "SubmitWashExit" -> {
        require(payload, "washExecutionId", "submittedByStudentAccountId", "materials", "submittedAt",
            "expectedVersion");
        requireMaterials(payload.get("materials"));
        requireIntegral(payload, "expectedVersion");
      }
      case "CompleteWashExecution" -> {
        require(payload, "washExecutionId", "completedByAccountId", "finalMaterials", "completedAt",
            "expectedVersion");
        requireMaterials(payload.get("finalMaterials"));
        requireIntegral(payload, "expectedVersion");
      }
      case "CancelWashExecutionByClinic" -> {
        require(payload, "washExecutionId", "cancellationSubreason", "cancelledByAccountId", "cancelledAt",
            "expectedVersion");
        requireIntegral(payload, "expectedVersion");
      }
      default -> throw invalid("UNSUPPORTED_COMMAND");
    }
  }

  private WashExecutionCommand.ResolvePendingReassignment resolvePendingReassignment(JsonNode payload) {
    if (fields(payload).containsAll(REASSIGNMENT_LEGACY_ALIASES)) {
      return codec.convertNode(payload, WashExecutionCommand.ResolvePendingReassignment.class);
    }
    ObjectNode normalized = (ObjectNode) payload.deepCopy();
    normalized.set("cabinId", payload.get("selectedCabinId"));
    normalized.set("tankId", payload.get("selectedTankId"));
    normalized.set("resolvedByAccountId", payload.get("assignedByAccountId"));
    normalized.set("resolvedAt", payload.get("assignedAt"));
    normalized.remove(Set.of(
        "selectedCabinId", "selectedTankId", "assignedByAccountId", "assignedAt"));
    return codec.convertNode(normalized, WashExecutionCommand.ResolvePendingReassignment.class);
  }

  private static void requireUnambiguousReassignmentShape(JsonNode payload) {
    Set<String> actual = fields(payload);
    boolean hasCanonical = !java.util.Collections.disjoint(actual, REASSIGNMENT_CANONICAL_ALIASES);
    boolean hasLegacy = !java.util.Collections.disjoint(actual, REASSIGNMENT_LEGACY_ALIASES);
    boolean canonicalComplete = actual.containsAll(REASSIGNMENT_CANONICAL_ALIASES);
    boolean legacyComplete = actual.containsAll(REASSIGNMENT_LEGACY_ALIASES);
    if (hasCanonical == hasLegacy || (hasCanonical && !canonicalComplete)
        || (hasLegacy && !legacyComplete)) {
      throw invalid("AMBIGUOUS_OR_INVALID_REASSIGNMENT_PAYLOAD");
    }
    require(payload, "washExecutionId", "expectedVersion");
    Set<String> selected = hasCanonical
        ? REASSIGNMENT_CANONICAL_ALIASES : REASSIGNMENT_LEGACY_ALIASES;
    require(payload, selected.toArray(String[]::new));
  }

  private static Set<String> fields(JsonNode payload) {
    Set<String> fields = new HashSet<>();
    fields.addAll(payload.propertyNames());
    return fields;
  }

  private static void requireMaterials(JsonNode materials) {
    if (materials == null || !materials.isObject()) {
      throw invalid("INVALID_COMMAND_PAYLOAD");
    }
    require(materials, "packageCount", "greenPaperCassette8Count", "greenPaperCassette10Count",
        "witnessTapePortionCount");
    requireIntegral(materials, "packageCount");
    requireIntegral(materials, "greenPaperCassette8Count");
    requireIntegral(materials, "greenPaperCassette10Count");
    requireIntegral(materials, "witnessTapePortionCount");
  }

  private static void require(JsonNode payload, String... fields) {
    for (String field : fields) {
      if (!payload.has(field) || payload.get(field) == null || payload.get(field).isNull()) {
        throw invalid("COMMAND_FIELD_REQUIRED:" + field);
      }
    }
  }

  private static void requireBoolean(JsonNode payload, String field) {
    if (!payload.get(field).isBoolean()) {
      throw invalid("COMMAND_FIELD_BOOLEAN_REQUIRED:" + field);
    }
  }

  private static void requireIntegral(JsonNode payload, String field) {
    if (!payload.get(field).isIntegralNumber()) {
      throw invalid("COMMAND_FIELD_INTEGER_REQUIRED:" + field);
    }
  }

  private static IllegalArgumentException invalid(String code) {
    return new IllegalArgumentException(code);
  }
}
