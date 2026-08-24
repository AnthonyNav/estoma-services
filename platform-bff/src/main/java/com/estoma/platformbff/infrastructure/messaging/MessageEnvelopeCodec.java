package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.operations.IncomingResult;
import com.estoma.platformbff.application.port.MessageSerializer;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.CommandEnvelope;
import com.estoma.platformbff.contracts.messaging.EventEnvelope;
import com.estoma.platformbff.contracts.messaging.QueryEnvelope;
import com.estoma.platformbff.contracts.messaging.ResultEnvelope;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class MessageEnvelopeCodec implements MessageSerializer {
  private final ObjectMapper mapper;

  public MessageEnvelopeCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  @Override
  public <T> T read(String json, Class<T> type) {
    return mapper.readValue(json, type);
  }

  public JsonNode readTree(String json) {
    return mapper.readValue(json, JsonNode.class);
  }

  public CommandEnvelope readCommand(byte[] body) {
    return mapper.readValue(body, CommandEnvelope.class);
  }

  public ResultEnvelope readResult(byte[] body) {
    return mapper.readValue(body, ResultEnvelope.class);
  }

  public QueryEnvelope readQuery(byte[] body) {
    return mapper.readValue(body, QueryEnvelope.class);
  }

  public EventEnvelope readEvent(byte[] body) {
    return mapper.readValue(body, EventEnvelope.class);
  }

  public IncomingResult readIncomingResult(byte[] body) {
    ResultEnvelope result = readResult(body);
    JsonNode payload = result.payload();
    JsonNode data = payload == null || !payload.isObject() ? null : payload.get("data");
    JsonNode error = payload == null || !payload.isObject() ? null : payload.get("error");
    String status = payload != null
            && payload.isObject()
            && payload.hasNonNull("status")
            && payload.get("status").isTextual()
        ? payload.get("status").asText()
        : null;
    String errorCode = error != null
            && error.isObject()
            && error.hasNonNull("code")
            && error.get("code").isTextual()
        ? error.get("code").asText()
        : null;
    String errorDetails = error == null || error.isNull() ? null : mapper.writeValueAsString(error);
    return new IncomingResult(
        result.messageId(), result.messageType(), result.messageVersion(),
        result.messageKind(), result.producer(), result.occurredAt(), result.correlationId(),
        result.causationId(), result.operationId(), result.aggregateId(), result.aggregateVersion(),
        status, data == null || data.isNull() ? null : mapper.writeValueAsString(data),
        errorCode, errorDetails, data == null || !data.isObject() ? null : object(data));
  }

  public String canonical(JsonNode value) {
    return mapper.writeValueAsString(value);
  }

  public ActorContext readActor(String json) {
    return json == null ? ActorContext.anonymous() : mapper.readValue(json, ActorContext.class);
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
      var result = new ArrayList<>();
      node.forEach(child -> result.add(value(child)));
      return result;
    }
    if (node.isBoolean()) return node.asBoolean();
    if (node.isIntegralNumber()) return node.longValue();
    if (node.isNumber()) return node.doubleValue();
    return node.asText();
  }
}
