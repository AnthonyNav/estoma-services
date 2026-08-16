package com.estoma.washadministration.infrastructure.messaging.outbound;

import com.estoma.washadministration.contracts.MessageKind;
import com.estoma.washadministration.contracts.event.AdministrativeClosureCancelledPayload;
import com.estoma.washadministration.contracts.event.AdministrativeClosureScheduledPayload;
import com.estoma.washadministration.contracts.event.CabinActivatedPayload;
import com.estoma.washadministration.contracts.event.CabinDeactivatedPayload;
import com.estoma.washadministration.contracts.event.CabinRegisteredPayload;
import com.estoma.washadministration.contracts.event.CabinRetiredPayload;
import com.estoma.washadministration.contracts.event.CabinUpdatedPayload;
import com.estoma.washadministration.contracts.event.ResourcePriorityConfiguredPayload;
import com.estoma.washadministration.contracts.event.ServiceCalendarActivatedPayload;
import com.estoma.washadministration.contracts.event.ServiceCalendarCreatedPayload;
import com.estoma.washadministration.contracts.event.ServiceCalendarDeactivatedPayload;
import com.estoma.washadministration.contracts.event.ServiceCalendarUpdatedPayload;
import com.estoma.washadministration.contracts.event.ServiceScheduleConfiguredPayload;
import com.estoma.washadministration.contracts.event.ServiceScheduleDeactivatedPayload;
import com.estoma.washadministration.contracts.event.ServiceScheduleUpdatedPayload;
import com.estoma.washadministration.contracts.event.TankActivatedPayload;
import com.estoma.washadministration.contracts.event.TankDeactivatedPayload;
import com.estoma.washadministration.contracts.event.TankRegisteredPayload;
import com.estoma.washadministration.contracts.event.TankRetiredPayload;
import com.estoma.washadministration.contracts.event.TankUpdatedPayload;
import com.estoma.washadministration.contracts.query.GetAccountAuthorizationSnapshotRequest;
import com.estoma.washadministration.contracts.query.ListAccountAuthorizationSnapshotsPageRequest;
import com.estoma.washadministration.contracts.result.ResultPayload;
import com.estoma.washadministration.contracts.result.ResultStatus;
import com.estoma.washadministration.infrastructure.messaging.EnvelopeJsonCodec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class OutboundWirePayloadMapper {

  private final EnvelopeJsonCodec codec;

  public OutboundWirePayloadMapper(EnvelopeJsonCodec codec) {
    this.codec = codec;
  }

  public JsonNode map(MessageKind kind, String messageType, String payloadJson) {
    JsonNode raw = codec.readTree(payloadJson);
    if (raw == null || raw.isNull() || !raw.isObject()) {
      throw new IllegalArgumentException("Outbox payload object is required");
    }
    return switch (kind) {
      case EVENT -> mapEvent(messageType, raw);
      case RESULT -> validateResult(raw);
      case QUERY -> mapQuery(messageType, raw);
      case COMMAND -> throw new IllegalArgumentException("wash-administration does not publish Commands");
    };
  }

  private JsonNode mapEvent(String type, JsonNode raw) {
    return switch (type) {
      case "ServiceCalendarCreated" -> canonical(raw, ServiceCalendarCreatedPayload.class);
      case "ServiceCalendarUpdated" -> canonical(raw, ServiceCalendarUpdatedPayload.class);
      case "ServiceCalendarActivated" -> canonical(raw, ServiceCalendarActivatedPayload.class);
      case "ServiceCalendarDeactivated" -> canonical(raw, ServiceCalendarDeactivatedPayload.class);
      case "ServiceScheduleConfigured" -> canonical(raw, ServiceScheduleConfiguredPayload.class);
      case "ServiceScheduleUpdated" -> canonical(raw, ServiceScheduleUpdatedPayload.class);
      case "ServiceScheduleDeactivated" -> canonical(raw, ServiceScheduleDeactivatedPayload.class);
      case "AdministrativeClosureScheduled" -> canonical(raw, AdministrativeClosureScheduledPayload.class);
      case "AdministrativeClosureCancelled" -> canonical(raw, AdministrativeClosureCancelledPayload.class);
      case "CabinRegistered" -> canonical(raw, CabinRegisteredPayload.class);
      case "CabinUpdated" -> canonical(raw, CabinUpdatedPayload.class);
      case "CabinActivated" -> canonical(raw, CabinActivatedPayload.class);
      case "CabinDeactivated" -> canonical(raw, CabinDeactivatedPayload.class);
      case "CabinRetired" -> canonical(raw, CabinRetiredPayload.class);
      case "TankRegistered" -> canonical(raw, TankRegisteredPayload.class);
      case "TankUpdated" -> canonical(raw, TankUpdatedPayload.class);
      case "TankActivated" -> canonical(raw, TankActivatedPayload.class);
      case "TankDeactivated" -> canonical(raw, TankDeactivatedPayload.class);
      case "TankRetired" -> canonical(raw, TankRetiredPayload.class);
      case "ResourcePriorityConfigured" -> canonical(raw, ResourcePriorityConfiguredPayload.class);
      default -> throw new IllegalArgumentException("unsupported Event payload contract: " + type);
    };
  }

  private JsonNode mapQuery(String type, JsonNode raw) {
    return switch (type) {
      case "GetAccountAuthorizationSnapshot" -> canonical(raw, GetAccountAuthorizationSnapshotRequest.class);
      case "ListAccountAuthorizationSnapshotsPage" -> canonical(raw, ListAccountAuthorizationSnapshotsPageRequest.class);
      default -> throw new IllegalArgumentException("unsupported Query payload contract: " + type);
    };
  }

  private JsonNode validateResult(JsonNode raw) {
    ResultPayload result = codec.convertNode(raw, ResultPayload.class);
    if (result.status() == null) {
      throw new IllegalArgumentException("Result status is required");
    }
    if (result.status() == ResultStatus.SUCCEEDED) {
      if (result.error() != null) {
        throw new IllegalArgumentException("SUCCEEDED Result cannot contain error");
      }
      return raw;
    }
    if (result.status() == ResultStatus.REJECTED || result.status() == ResultStatus.FAILED) {
      requireErrorCode(result);
      return raw;
    }
    if (result.status() == ResultStatus.EXPIRED && result.error() != null) {
      requireErrorCode(result);
    }
    return raw;
  }

  private static void requireErrorCode(ResultPayload result) {
    if (result.error() == null || result.error().code() == null || result.error().code().isBlank()) {
      throw new IllegalArgumentException(result.status() + " Result requires error.code");
    }
  }

  private <T> JsonNode canonical(JsonNode raw, Class<T> type) {
    return codec.toJsonNode(codec.convertNode(raw, type));
  }
}
