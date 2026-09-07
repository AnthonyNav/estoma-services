package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.WashExitMaterialsProjectionPort;
import com.estoma.platformbff.application.port.WashCompletionProjectionPort;
import com.estoma.platformbff.application.projection.ProjectionEvent;
import com.estoma.platformbff.application.projection.ProjectionEventRuntime;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Infrastructure adapter: decodes the wire tree and delegates to the application trust boundary. */
@Component
public class ProjectionEventDispatcher {
  public enum DispatchOutcome { APPLIED, UNSUPPORTED, UNTRUSTED }

  private final ProjectionEventRuntime runtime;
  private final WashExitMaterialsProjectionPort exitMaterials;
  private final WashCompletionProjectionPort completion;

  @Autowired
  public ProjectionEventDispatcher(ProjectionEventRuntime runtime,
      WashExitMaterialsProjectionPort exitMaterials,
      WashCompletionProjectionPort completion) {
    this.runtime = runtime;
    this.exitMaterials = exitMaterials;
    this.completion = completion;
  }

  /** Compatibility constructor for focused tests that do not exercise the PR5 material projection. */
  public ProjectionEventDispatcher(ProjectionEventRuntime runtime) {
    this(runtime,
        (executionId, packages, cassette8, cassette10, witnessTape, version, updatedAt) -> {},
        (executionId, packages, cassette8, cassette10, witnessTape, version, completedAt) -> {});
  }

  @Transactional
  public DispatchOutcome dispatch(EventEnvelope event) {
    if (event == null || event.messageKind() != MessageKind.EVENT || event.payload() == null
        || !event.payload().isObject()) {
      return DispatchOutcome.UNTRUSTED;
    }
    try {
      validateWashEntryRejectedV2(event);
      SubmittedExitMaterials submitted = submittedExitMaterials(event);
      CompletedExitMaterials completed = completedExitMaterials(event);
      var outcome = runtime.dispatch(new ProjectionEvent(
          event.messageId(), event.messageType(), event.messageVersion(), event.producer(),
          event.occurredAt(), event.correlationId(), event.causationId(), event.aggregateId(),
          event.aggregateVersion(), object(event.payload())));
      if (outcome == ProjectionEventRuntime.DispatchOutcome.APPLIED && submitted != null) {
        exitMaterials.projectSubmittedExitMaterials(event.aggregateId(), submitted.packageCount(),
            submitted.greenPaperCassette8Count(), submitted.greenPaperCassette10Count(),
            submitted.witnessTapePortionCount(), event.aggregateVersion(), event.occurredAt());
      }
      if (outcome == ProjectionEventRuntime.DispatchOutcome.APPLIED && completed != null) {
        completion.projectCompletion(event.aggregateId(), completed.packageCount(),
            completed.greenPaperCassette8Count(), completed.greenPaperCassette10Count(),
            completed.witnessTapePortionCount(), event.aggregateVersion(), completed.completedAt());
      }
      return switch (outcome) {
        case APPLIED -> DispatchOutcome.APPLIED;
        case UNSUPPORTED -> DispatchOutcome.UNSUPPORTED;
        case UNTRUSTED -> DispatchOutcome.UNTRUSTED;
      };
    } catch (IllegalArgumentException invalid) {
      return DispatchOutcome.UNTRUSTED;
    }
  }

  private static void validateWashEntryRejectedV2(EventEnvelope event) {
    if (!"wash-execution".equals(event.producer())
        || !"WashEntryRejected".equals(event.messageType())
        || event.messageVersion() != 2) return;
    JsonNode payload = event.payload();
    boolean hasIdentity = payload.has("identityConfirmed");
    boolean hasRequirements = payload.has("requirementsSatisfied");
    if (hasIdentity != hasRequirements
        || (hasIdentity && (!payload.path("identityConfirmed").isBoolean()
            || !payload.path("requirementsSatisfied").isBoolean()))) {
      throw new IllegalArgumentException("V2 entry checks must be an absent or boolean pair");
    }
  }

  private static SubmittedExitMaterials submittedExitMaterials(EventEnvelope event) {
    if (!"wash-execution".equals(event.producer()) || !"WashExitSubmitted".equals(event.messageType())) {
      return null;
    }
    JsonNode payload = event.payload();
    boolean hasAny = payload.hasNonNull("packageCount")
        || payload.hasNonNull("greenPaperCassette8Count")
        || payload.hasNonNull("greenPaperCassette10Count")
        || payload.hasNonNull("witnessTapePortionCount");
    if (!hasAny) return null;
    return new SubmittedExitMaterials(
        nonnegativeInt(payload, "packageCount"),
        nonnegativeInt(payload, "greenPaperCassette8Count"),
        nonnegativeInt(payload, "greenPaperCassette10Count"),
        nonnegativeInt(payload, "witnessTapePortionCount"));
  }

  private static CompletedExitMaterials completedExitMaterials(EventEnvelope event) {
    if (!"wash-execution".equals(event.producer())
        || !"WashExecutionCompleted".equals(event.messageType())) return null;
    JsonNode payload = event.payload();
    Instant completedAt;
    try {
      completedAt = Instant.parse(payload.path("completedAt").asText());
    } catch (RuntimeException invalid) {
      throw new IllegalArgumentException("completedAt must be an instant", invalid);
    }
    CompletedExitMaterials completed = new CompletedExitMaterials(
        nonnegativeInt(payload, "finalPackageCount"),
        nonnegativeInt(payload, "finalGreenPaperCassette8Count"),
        nonnegativeInt(payload, "finalGreenPaperCassette10Count"),
        nonnegativeInt(payload, "finalWitnessTapePortionCount"), completedAt);
    if ((long) completed.packageCount() + completed.greenPaperCassette8Count()
        + completed.greenPaperCassette10Count() + completed.witnessTapePortionCount() == 0L) {
      throw new IllegalArgumentException("final exit materials cannot be empty");
    }
    return completed;
  }

  private static int nonnegativeInt(JsonNode payload, String field) {
    if (!payload.has(field) || !payload.path(field).isIntegralNumber()
        || !payload.path(field).canConvertToInt()) {
      throw new IllegalArgumentException(field + " must be an integer");
    }
    int value = payload.path(field).asInt();
    if (value < 0) throw new IllegalArgumentException(field + " cannot be negative");
    return value;
  }

  private static Map<String, Object> object(JsonNode node) {
    Map<String, Object> result = new HashMap<>();
    node.properties().forEach(entry -> result.put(entry.getKey(), value(entry.getValue())));
    return result;
  }

  private static Object value(JsonNode node) {
    if (node == null || node.isNull()) return null;
    if (node.isObject()) return object(node);
    if (node.isArray()) {
      var values = new ArrayList<>();
      node.forEach(child -> values.add(value(child)));
      return values;
    }
    if (node.isBoolean()) return node.asBoolean();
    if (node.isIntegralNumber()) return node.longValue();
    if (node.isNumber()) return node.doubleValue();
    return node.asText();
  }

  private record SubmittedExitMaterials(
      int packageCount,
      int greenPaperCassette8Count,
      int greenPaperCassette10Count,
      int witnessTapePortionCount) {}

  private record CompletedExitMaterials(
      int packageCount,
      int greenPaperCassette8Count,
      int greenPaperCassette10Count,
      int witnessTapePortionCount,
      Instant completedAt) {}
}
