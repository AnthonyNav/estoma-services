package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.RequestFingerprintPort;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Component
public class CanonicalRequestFingerprint implements RequestFingerprintPort {
  private final ObjectMapper mapper;

  public CanonicalRequestFingerprint(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public NormalizedRequest normalize(
      String publicOperation,
      String messageType,
      String payloadJson,
      UUID aggregateId,
      Integer aggregateVersion,
      ActorContext actor) {
    JsonNode canonicalPayload = canonicalize(mapper.readValue(payloadJson, JsonNode.class));
    String canonicalPayloadJson = mapper.writeValueAsString(canonicalPayload);

    ObjectNode functionalRequest = mapper.createObjectNode();
    functionalRequest.put("publicOperation", publicOperation);
    functionalRequest.put("messageType", messageType);
    if (aggregateId == null) functionalRequest.putNull("aggregateId");
    else functionalRequest.put("aggregateId", aggregateId.toString());
    if (aggregateVersion == null) functionalRequest.putNull("aggregateVersion");
    else functionalRequest.put("aggregateVersion", aggregateVersion);
    JsonNode actorJson = mapper.readValue(mapper.writeValueAsString(actor), JsonNode.class);
    functionalRequest.set("actor", canonicalize(actorJson));
    functionalRequest.set("payload", canonicalPayload);

    String material = mapper.writeValueAsString(canonicalize(functionalRequest));
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      String fingerprint = HexFormat.of().formatHex(
          digest.digest(material.getBytes(StandardCharsets.UTF_8)));
      return new NormalizedRequest(canonicalPayloadJson, fingerprint);
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }

  private JsonNode canonicalize(JsonNode value) {
    if (value == null || value.isNull() || value.isValueNode()) {
      return value;
    }
    if (value.isArray()) {
      ArrayNode result = mapper.createArrayNode();
      for (JsonNode child : value) {
        result.add(canonicalize(child));
      }
      return result;
    }
    ObjectNode result = mapper.createObjectNode();
    Map<String, JsonNode> sorted = new TreeMap<>();
    value.properties().forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
    sorted.forEach((key, child) -> result.set(key, canonicalize(child)));
    return result;
  }
}
