package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.StructuredDataComparator;
import java.util.Objects;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonStructuredDataComparator implements StructuredDataComparator {
  private final ObjectMapper mapper;

  public JsonStructuredDataComparator(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public boolean equivalent(String firstJson, String secondJson) {
    if (firstJson == null || secondJson == null) {
      return firstJson == null && secondJson == null;
    }
    try {
      JsonNode first = mapper.readValue(firstJson, JsonNode.class);
      JsonNode second = mapper.readValue(secondJson, JsonNode.class);
      return first.equals(second);
    } catch (RuntimeException invalidJson) {
      return Objects.equals(firstJson, secondJson);
    }
  }
}
