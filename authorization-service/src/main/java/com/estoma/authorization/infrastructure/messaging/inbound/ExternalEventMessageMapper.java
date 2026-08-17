package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.application.projection.AcademicEligibilityProjectionEvent;
import com.estoma.authorization.application.projection.AccountProjectionEvent;
import com.estoma.authorization.application.projection.PersonProjectionEvent;
import com.estoma.authorization.application.projection.ProjectionEvent;
import com.estoma.authorization.application.projection.ProjectionEventMetadata;
import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.domain.model.LoginType;
import com.estoma.authorization.domain.model.ProjectedStatus;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
final class ExternalEventMessageMapper {

  private final com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec codec;

  ExternalEventMessageMapper(com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec codec) {
    this.codec = codec;
  }

  ProjectionEvent map(MessageEnvelope envelope) {
    ProjectionEventMetadata metadata = new ProjectionEventMetadata(
        envelope.messageId(),
        envelope.messageType(),
        envelope.messageVersion(),
        envelope.producer(),
        envelope.occurredAt(),
        envelope.correlationId(),
        envelope.causationId(),
        envelope.operationId(),
        envelope.idempotencyKey(),
        WireValues.actor(envelope.actor()),
        envelope.aggregateId(),
        envelope.aggregateVersion());
    return switch (envelope.messageType()) {
      case "PersonCreated" -> person(metadata, envelope.payload(), "ACTIVE", Set.of(
          "personId", "firstName", "paternalSurname", "maternalSurname", "institutionalEmail", "phone", "status", "version", "createdAt"));
      case "PersonActivated" -> person(metadata, envelope.payload(), "ACTIVE", Set.of(
          "personId", "status", "version", "activatedAt"));
      case "PersonDeactivated" -> person(metadata, envelope.payload(), "INACTIVE", Set.of(
          "personId", "status", "version", "deactivatedAt", "reasonCode", "reasonDetail"));
      case "AccountCreated" -> accountCreated(metadata, envelope.payload());
      case "AccountActivated" -> accountTransition(metadata, envelope.payload(), "ACTIVE", Set.of(
          "accountId", "personId", "previousStatus", "status", "activationReason", "activatedAt"));
      case "AccountDeactivated" -> accountTransition(metadata, envelope.payload(), "INACTIVE", Set.of(
          "accountId", "personId", "previousStatus", "status", "reasonCode", "reasonDetail", "deactivatedAt"));
      case "StudentAcademicEligibilityChanged" -> eligibility(metadata, envelope.payload());
      default -> throw new InvalidMessageException("unsupported external Event");
    };
  }

  private PersonProjectionEvent person(
      ProjectionEventMetadata metadata, JsonNode payload, String expectedStatus, Set<String> allowed) {
    Set<String> required = "PersonDeactivated".equals(metadata.messageType())
        ? Set.of("personId", "status", "version", "deactivatedAt", "reasonCode")
        : allowed;
    WireValues.fields(payload, required, allowed);
    ProjectedStatus status = WireValues.enumValue(payload, "status", ProjectedStatus.class);
    if (!expectedStatus.equals(status.name())) {
      throw new InvalidMessageException("Person status does not match Event type");
    }
    if ("PersonDeactivated".equals(metadata.messageType())) {
      String reasonCode = WireValues.text(payload, "reasonCode");
      String reasonDetail = WireValues.optionalText(payload, "reasonDetail");
      if ("OTHER".equals(reasonCode) != (reasonDetail != null)) {
        throw new InvalidMessageException("Person reasonDetail is conditional on OTHER");
      }
    }
    if (!"users".equals(metadata.producer())
        || !metadata.aggregateId().equals(WireValues.uuid(payload, "personId"))
        || metadata.aggregateVersion() != WireValues.positiveInt(payload, "version")) {
      throw new InvalidMessageException("invalid Person Event owner or aggregate metadata");
    }
    return new PersonProjectionEvent(
        metadata,
        WireValues.uuid(payload, "personId"),
        status,
        codec.write(payload));
  }

  private AccountProjectionEvent accountCreated(ProjectionEventMetadata metadata, JsonNode payload) {
    Set<String> fields = Set.of("accountId", "personId", "loginType", "status", "createdAt");
    WireValues.fields(payload, fields, fields);
    ProjectedStatus status = WireValues.enumValue(payload, "status", ProjectedStatus.class);
    if (status != ProjectedStatus.INACTIVE) {
      throw new InvalidMessageException("AccountCreated requires INACTIVE status");
    }
    if (!"authentication".equals(metadata.producer())
        || !metadata.aggregateId().equals(WireValues.uuid(payload, "accountId"))) {
      throw new InvalidMessageException("invalid Account Event owner or aggregate metadata");
    }
    return new AccountProjectionEvent(
        metadata,
        WireValues.uuid(payload, "accountId"),
        WireValues.uuid(payload, "personId"),
        WireValues.enumValue(payload, "loginType", LoginType.class),
        status,
        codec.write(payload));
  }

  private AccountProjectionEvent accountTransition(
      ProjectionEventMetadata metadata, JsonNode payload, String expectedStatus, Set<String> fields) {
    Set<String> required = "AccountDeactivated".equals(metadata.messageType())
        ? Set.of("accountId", "personId", "previousStatus", "status", "reasonCode", "deactivatedAt")
        : fields;
    WireValues.fields(payload, required, fields);
    ProjectedStatus status = WireValues.enumValue(payload, "status", ProjectedStatus.class);
    if (!expectedStatus.equals(status.name())) {
      throw new InvalidMessageException("Account status does not match Event type");
    }
    if ("AccountDeactivated".equals(metadata.messageType())) {
      String reasonDetail = WireValues.optionalText(payload, "reasonDetail");
      if ("OTHER".equals(WireValues.text(payload, "reasonCode")) != (reasonDetail != null)) {
        throw new InvalidMessageException("Account reasonDetail is conditional on OTHER");
      }
    }
    if (!"authentication".equals(metadata.producer())
        || !metadata.aggregateId().equals(WireValues.uuid(payload, "accountId"))) {
      throw new InvalidMessageException("invalid Account Event owner or aggregate metadata");
    }
    return new AccountProjectionEvent(
        metadata,
        WireValues.uuid(payload, "accountId"),
        WireValues.uuid(payload, "personId"),
        null,
        status,
        codec.write(payload));
  }

  private AcademicEligibilityProjectionEvent eligibility(
      ProjectionEventMetadata metadata, JsonNode payload) {
    Set<String> fields = Set.of(
        "eligibilityId",
        "studentAcademicProfileId",
        "accountId",
        "systemCode",
        "eligible",
        "policyVersion",
        "eligibilityVersion",
        "academicProfileVersion",
        "evaluatedAt");
    WireValues.fields(payload, fields, fields);
    if (!"academic".equals(metadata.producer())
        || !metadata.aggregateId().equals(WireValues.uuid(payload, "eligibilityId"))
        || metadata.aggregateVersion() != WireValues.positiveInt(payload, "eligibilityVersion")) {
      throw new InvalidMessageException("invalid eligibility Event owner or aggregate metadata");
    }
    return new AcademicEligibilityProjectionEvent(
        metadata,
        WireValues.uuid(payload, "eligibilityId"),
        WireValues.uuid(payload, "studentAcademicProfileId"),
        WireValues.text(payload, "systemCode"),
        WireValues.uuid(payload, "accountId"),
        WireValues.bool(payload, "eligible"),
        WireValues.positiveInt(payload, "policyVersion"),
        WireValues.positiveInt(payload, "eligibilityVersion"),
        WireValues.positiveInt(payload, "academicProfileVersion"),
        WireValues.instant(payload, "evaluatedAt"),
        codec.write(payload));
  }
}
