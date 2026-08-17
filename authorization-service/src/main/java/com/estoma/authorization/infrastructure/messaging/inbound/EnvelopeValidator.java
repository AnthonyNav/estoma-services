package com.estoma.authorization.infrastructure.messaging.inbound;

import com.estoma.authorization.contracts.MessageEnvelope;
import com.estoma.authorization.contracts.MessageKind;
import com.estoma.authorization.contracts.WireActor;
import com.estoma.authorization.infrastructure.messaging.EnvelopeJsonCodec;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Strict V1 envelope validation before a wire message reaches Application. */
@Component
final class EnvelopeValidator {

  private static final Set<String> ROOT_FIELDS = Set.of(
      "messageId",
      "messageType",
      "messageVersion",
      "messageKind",
      "producer",
      "occurredAt",
      "correlationId",
      "causationId",
      "operationId",
      "idempotencyKey",
      "aggregateId",
      "aggregateVersion",
      "actor",
      "payload");
  private static final Set<String> COMMON_REQUIRED = Set.of(
      "messageId",
      "messageType",
      "messageVersion",
      "messageKind",
      "producer",
      "occurredAt",
      "correlationId",
      "actor",
      "payload");

  private final EnvelopeJsonCodec codec;

  EnvelopeValidator(EnvelopeJsonCodec codec) {
    this.codec = codec;
  }

  MessageEnvelope validate(JsonNode raw, MessageKind expectedKind, Set<String> allowedTypes) {
    WireValues.object(raw, "envelope");
    WireValues.fields(raw, COMMON_REQUIRED, ROOT_FIELDS);
    if (WireValues.positiveInt(raw, "messageVersion") != 1) {
      throw new InvalidMessageException("unsupported messageVersion");
    }
    MessageKind actual;
    try {
      actual = MessageKind.valueOf(WireValues.text(raw, "messageKind"));
    } catch (IllegalArgumentException exception) {
      throw new InvalidMessageException("unsupported messageKind", exception);
    }
    if (actual != expectedKind) {
      throw new InvalidMessageException("unexpected messageKind: " + actual);
    }
    String type = WireValues.text(raw, "messageType");
    if (!allowedTypes.contains(type)) {
      throw new InvalidMessageException("unsupported messageType: " + type);
    }
    WireValues.uuid(raw, "messageId");
    WireValues.instant(raw, "occurredAt");
    WireValues.uuid(raw, "correlationId");
    if (raw.has("causationId")) {
      WireValues.uuid(raw, "causationId");
    }
    if (raw.has("operationId")) {
      WireValues.uuid(raw, "operationId");
    }
    if (raw.has("idempotencyKey")) {
      WireValues.uuid(raw, "idempotencyKey");
    }
    if (raw.has("aggregateId")) {
      WireValues.uuid(raw, "aggregateId");
    }
    if (raw.has("aggregateVersion")) {
      WireValues.positiveInt(raw, "aggregateVersion");
    }
    validateActor(raw.get("actor"));
    WireValues.object(raw.get("payload"), "payload");
    WireValues.rejectNulls(raw.get("payload"), "payload");
    validateKindShape(raw, expectedKind);
    try {
      return codec.decode(raw);
    } catch (RuntimeException exception) {
      throw new InvalidMessageException("invalid envelope", exception);
    }
  }

  private static void validateKindShape(JsonNode raw, MessageKind kind) {
    switch (kind) {
      case COMMAND -> {
        WireValues.uuid(raw, "operationId");
        WireValues.uuid(raw, "idempotencyKey");
      }
      case EVENT -> {
        WireValues.uuid(raw, "causationId");
        WireValues.uuid(raw, "aggregateId");
        WireValues.positiveInt(raw, "aggregateVersion");
      }
      case QUERY -> {
        WireValues.uuid(raw, "operationId");
        forbidden(raw, "idempotencyKey");
      }
      case RESULT -> {
        WireValues.uuid(raw, "causationId");
        WireValues.uuid(raw, "operationId");
        forbidden(raw, "idempotencyKey");
      }
    }
  }

  private static void validateActor(JsonNode actor) {
    WireValues.object(actor, "actor");
    String type = WireValues.text(actor, "type");
    if ("ACCOUNT".equals(type)) {
      WireValues.fields(actor, Set.of("type", "accountId"), Set.of("type", "accountId"));
      WireValues.uuid(actor, "accountId");
      return;
    }
    if ("SYSTEM".equals(type)) {
      WireValues.fields(actor, Set.of("type", "service"), Set.of("type", "service"));
      WireValues.text(actor, "service");
      return;
    }
    throw new InvalidMessageException("actor.type is unsupported");
  }

  private static void forbidden(JsonNode raw, String field) {
    if (raw.has(field)) {
      throw new InvalidMessageException(field + " is not applicable to this messageKind");
    }
  }
}
