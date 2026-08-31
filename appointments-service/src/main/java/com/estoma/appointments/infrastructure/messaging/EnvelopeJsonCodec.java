package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.contracts.MessageKind;
import com.estoma.appointments.contracts.WireActor;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public final class EnvelopeJsonCodec {
  private final ObjectMapper mapper;
  public EnvelopeJsonCodec(ObjectMapper mapper) { this.mapper = mapper; }
  public JsonNode readTree(byte[] body) { return mapper.readTree(body); }
  public JsonNode readTree(String body) { return mapper.readTree(body); }
  public byte[] encode(MessageEnvelope envelope) {
    ObjectNode root = mapper.createObjectNode();
    root.put("messageId", envelope.messageId().toString());
    root.put("messageType", envelope.messageType());
    root.put("messageVersion", envelope.messageVersion());
    root.put("messageKind", envelope.messageKind().name());
    root.put("producer", envelope.producer());
    root.put("occurredAt", envelope.occurredAt().toString());
    root.put("correlationId", envelope.correlationId().toString());
    putUuid(root, "causationId", envelope.causationId());
    putUuid(root, "operationId", envelope.operationId());
    putText(root, "idempotencyKey", envelope.idempotencyKey());
    putUuid(root, "aggregateId", envelope.aggregateId());
    if (envelope.aggregateVersion() != null) root.put("aggregateVersion", envelope.aggregateVersion());
    if (envelope.actor() == null) throw new IllegalArgumentException("actor is required");
    ObjectNode actor = mapper.createObjectNode();
    actor.put("type", envelope.actor().type());
    putUuid(actor, "accountId", envelope.actor().accountId());
    putText(actor, "service", envelope.actor().service());
    root.set("actor", actor);
    if (envelope.payload() == null || !envelope.payload().isObject()) throw new IllegalArgumentException("payload must be an object");
    root.set("payload", envelope.payload());
    return mapper.writeValueAsBytes(root);
  }

  public MessageEnvelope decode(JsonNode root) {
    return new MessageEnvelope(requiredUuid(root, "messageId"), requiredText(root, "messageType"),
        requiredInt(root, "messageVersion"), MessageKind.valueOf(requiredText(root, "messageKind")),
        requiredText(root, "producer"), Instant.parse(requiredText(root, "occurredAt")),
        requiredUuid(root, "correlationId"), optionalUuid(root, "causationId"), optionalUuid(root, "operationId"),
        optionalText(root, "idempotencyKey"), optionalUuid(root, "aggregateId"), optionalInt(root, "aggregateVersion"),
        decodeActor(root.path("actor")), root.path("payload"));
  }

  public String write(JsonNode node) { return mapper.writeValueAsString(node); }
  private WireActor decodeActor(JsonNode node) {
    return new WireActor(requiredText(node, "type"), optionalUuid(node, "accountId"), optionalText(node, "service"));
  }
  private static UUID requiredUuid(JsonNode node, String field) {
    try { return UUID.fromString(requiredText(node, field)); }
    catch (RuntimeException exception) { throw new IllegalArgumentException(field + " must be a UUID", exception); }
  }
  private static UUID optionalUuid(JsonNode node, String field) {
    String value = optionalText(node, field); return value == null ? null : UUID.fromString(value);
  }
  private static int requiredInt(JsonNode node, String field) {
    if (!node.has(field) || !node.path(field).isInt()) throw new IllegalArgumentException(field + " must be an integer");
    return node.path(field).asInt();
  }
  private static Integer optionalInt(JsonNode node, String field) {
    return !node.has(field) || node.path(field).isNull() ? null : requiredInt(node, field);
  }
  private static String requiredText(JsonNode node, String field) {
    String value = optionalText(node, field);
    if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    return value;
  }
  private static String optionalText(JsonNode node, String field) {
    return !node.has(field) || node.path(field).isNull() || !node.path(field).isTextual() ? null : node.path(field).asText();
  }
  private static void putUuid(ObjectNode node, String field, UUID value) { if (value != null) node.put(field, value.toString()); }
  private static void putText(ObjectNode node, String field, String value) { if (value != null) node.put(field, value); }
}
