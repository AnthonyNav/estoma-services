package com.estoma.washadministration.infrastructure.config;

import com.estoma.washadministration.application.port.JsonCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public final class JacksonJsonCodec implements JsonCodec {

  private final ObjectMapper mapper;

  public JacksonJsonCodec(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public String write(Object value) {
    return mapper.writeValueAsString(value);
  }

  @Override
  public Object parse(String value) {
    return mapper.readValue(value, Object.class);
  }

  @Override
  public String canonical(Object value) {
    try {
      Object parsed = mapper.readValue(mapper.writeValueAsString(value), Object.class);
      return mapper.writeValueAsString(normalize(parsed));
    } catch (RuntimeException exception) {
      throw new IllegalArgumentException("payload cannot be canonicalized", exception);
    }
  }

  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> sorted = new TreeMap<>();
      map.forEach(
          (key, item) -> {
            if (item != null) {
              sorted.put(String.valueOf(key), normalize(item));
            }
          });
      return sorted;
    }
    if (value instanceof List<?> list) {
      List<Object> normalized = new ArrayList<>(list.size());
      list.forEach(item -> normalized.add(normalize(item)));
      return normalized;
    }
    if (value instanceof String string) {
      return string.trim();
    }
    return value;
  }
}
