package com.estoma.washexecution.infrastructure.config;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.port.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class JacksonJsonCodec implements JsonCodec {
  private final ObjectMapper mapper;

  public JacksonJsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String write(Object value) {
    try {
      return mapper.writeValueAsString(canonicalWireValue(value));
    } catch (Exception ex) {
      throw new IllegalStateException("JSON_SERIALIZATION_FAILED", ex);
    }
  }

  @Override
  public String fingerprint(Object value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(write(normalize(value)).getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException ex) {
      throw new IllegalStateException("SHA_256_UNAVAILABLE", ex);
    }
  }

  private Object normalize(Object value) {
    if (!(value instanceof Map<?, ?> map)) return value;
    TreeMap<String, Object> sorted = new TreeMap<>();
    map.forEach((key, item) -> sorted.put(String.valueOf(key), normalize(item)));
    return sorted;
  }

  private Object canonicalWireValue(Object value) {
    if (!(value instanceof ActorContext actor)) {
      return value;
    }
    ObjectNode node = mapper.createObjectNode();
    node.put("type", actor.type());
    if (actor.accountId() != null) {
      node.put("accountId", actor.accountId().toString());
    }
    if (actor.service() != null) {
      node.put("service", actor.service());
    }
    return node;
  }
}
