package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.recovery.ProjectionRecoveryResult;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.AccountSnapshot;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.EligibilitySnapshot;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.PersonSnapshot;
import com.estoma.authorization.application.recovery.ProjectionRecoveryResult.SnapshotItem;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
final class RecoveryResultMessageMapper {

  ProjectionRecoveryResult map(MessageEnvelope envelope) {
    if (envelope.idempotencyKey() != null) {
      throw new InvalidMessageException("idempotencyKey is not applicable to recovery Results");
    }
    JsonNode payload = envelope.payload();
    Set<String> fields = Set.of("status", "data", "error");
    WireValues.fields(payload, Set.of("status"), fields);
    ProjectionRecoveryResult.Status status = status(payload);
    boolean page = envelope.messageType().startsWith("List");
    validateStatusShape(payload, status, page);
    List<SnapshotItem> items = new ArrayList<>();
    String nextCursor = null;
    boolean hasMore = false;
    if (status == ProjectionRecoveryResult.Status.FOUND) {
      items.add(pointItem(envelope.messageType(), WireValues.object(payload.get("data"), "data")));
    } else if (status == ProjectionRecoveryResult.Status.SUCCEEDED) {
      JsonNode data = WireValues.object(payload.get("data"), "data");
      Set<String> pageFields = Set.of("items", "nextCursor", "hasMore");
      WireValues.fields(data, Set.of("items", "hasMore"), pageFields);
      hasMore = WireValues.bool(data, "hasMore");
      nextCursor = WireValues.optionalText(data, "nextCursor");
      if (hasMore != (nextCursor != null)) {
        throw new InvalidMessageException("nextCursor is conditional on hasMore");
      }
      for (JsonNode item : WireValues.array(data, "items")) {
        items.add(pageItem(envelope.messageType(), WireValues.object(item, "snapshot item")));
      }
    }
    return new ProjectionRecoveryResult(
        new ProjectionRecoveryResult.Metadata(
            envelope.messageId(),
            envelope.messageType(),
            envelope.producer(),
            envelope.occurredAt(),
            envelope.correlationId(),
            envelope.causationId(),
            envelope.operationId(),
            WireValues.actor(envelope.actor())),
        status,
        items,
        nextCursor,
        hasMore,
        errorCode(payload, status));
  }

  private static ProjectionRecoveryResult.Status status(JsonNode payload) {
    try {
      return ProjectionRecoveryResult.Status.valueOf(WireValues.text(payload, "status"));
    } catch (IllegalArgumentException exception) {
      throw new InvalidMessageException("unsupported recovery Result status", exception);
    }
  }

  private static void validateStatusShape(
      JsonNode payload, ProjectionRecoveryResult.Status status, boolean page) {
    boolean error = payload.has("error");
    boolean data = payload.has("data");
    if (page && status == ProjectionRecoveryResult.Status.SUCCEEDED && data && !error) {
      return;
    }
    if (!page && status == ProjectionRecoveryResult.Status.FOUND && data && !error) {
      return;
    }
    if (!page && status == ProjectionRecoveryResult.Status.NOT_FOUND && !data && !error) {
      return;
    }
    if ((status == ProjectionRecoveryResult.Status.REJECTED || status == ProjectionRecoveryResult.Status.FAILED)
        && !data
        && error) {
      JsonNode value = WireValues.object(payload.get("error"), "error");
      WireValues.fields(value, Set.of("code"), Set.of("code", "details"));
      if (value.has("details")) {
        WireValues.object(value.get("details"), "error.details");
      }
      return;
    }
    throw new InvalidMessageException("invalid recovery Result status/data/error combination");
  }

  private static String errorCode(JsonNode payload, ProjectionRecoveryResult.Status status) {
    if (status != ProjectionRecoveryResult.Status.REJECTED && status != ProjectionRecoveryResult.Status.FAILED) {
      return null;
    }
    return WireValues.text(WireValues.object(payload.get("error"), "error"), "code");
  }

  private static SnapshotItem pointItem(String type, JsonNode data) {
    return switch (type) {
      case "GetPersonStatusSnapshotResult" -> person(data);
      case "GetAccountStatusSnapshotResult" -> account(data);
      case "GetStudentAcademicEligibilitySnapshotResult" -> eligibility(data);
      default -> throw new InvalidMessageException("unsupported recovery point Result type");
    };
  }

  private static SnapshotItem pageItem(String type, JsonNode data) {
    return switch (type) {
      case "ListPersonStatusSnapshotsPageResult" -> person(data);
      case "ListAccountStatusSnapshotsPageResult" -> account(data);
      case "ListStudentAcademicEligibilitySnapshotsPageResult" -> eligibility(data);
      default -> throw new InvalidMessageException("unsupported recovery page Result type");
    };
  }

  private static PersonSnapshot person(JsonNode data) {
    Set<String> fields = Set.of("personId", "status", "sourceVersion", "updatedAt");
    WireValues.fields(data, fields, fields);
    return new PersonSnapshot(
        WireValues.uuid(data, "personId"),
        WireValues.enumValue(data, "status", ProjectedStatus.class),
        WireValues.positiveInt(data, "sourceVersion"),
        WireValues.instant(data, "updatedAt"));
  }

  private static AccountSnapshot account(JsonNode data) {
    Set<String> fields = Set.of("accountId", "personId", "loginType", "status", "sourceVersion", "updatedAt");
    WireValues.fields(data, fields, fields);
    return new AccountSnapshot(
        WireValues.uuid(data, "accountId"),
        WireValues.uuid(data, "personId"),
        WireValues.enumValue(data, "loginType", LoginType.class),
        WireValues.enumValue(data, "status", ProjectedStatus.class),
        WireValues.positiveInt(data, "sourceVersion"),
        WireValues.instant(data, "updatedAt"));
  }

  private static EligibilitySnapshot eligibility(JsonNode data) {
    Set<String> fields = Set.of(
        "eligibilityId",
        "studentAcademicProfileId",
        "accountId",
        "systemCode",
        "eligible",
        "policyVersion",
        "eligibilityVersion",
        "academicProfileVersion",
        "evaluatedAt",
        "updatedAt");
    WireValues.fields(data, fields, fields);
    return new EligibilitySnapshot(
        WireValues.uuid(data, "eligibilityId"),
        WireValues.uuid(data, "studentAcademicProfileId"),
        WireValues.uuid(data, "accountId"),
        WireValues.text(data, "systemCode"),
        WireValues.bool(data, "eligible"),
        WireValues.positiveInt(data, "policyVersion"),
        WireValues.positiveInt(data, "eligibilityVersion"),
        WireValues.positiveInt(data, "academicProfileVersion"),
        WireValues.instant(data, "evaluatedAt"),
        WireValues.instant(data, "updatedAt"));
  }
}
