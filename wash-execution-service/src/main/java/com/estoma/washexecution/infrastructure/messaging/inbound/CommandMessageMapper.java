package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.command.CommandMetadata;
import com.estoma.washexecution.application.command.CommandRequest;
import com.estoma.washexecution.application.command.WashExecutionCommand;
import com.estoma.washexecution.contracts.MessageEnvelope;
import com.estoma.washexecution.infrastructure.messaging.EnvelopeJsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class CommandMessageMapper {
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
      case "ResolvePendingReassignment" -> codec.convertPayload(e, WashExecutionCommand.ResolvePendingReassignment.class);
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
        require(payload, "washExecutionId", "cabinId", "tankId", "resolvedByAccountId", "resolvedAt",
            "expectedVersion");
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
