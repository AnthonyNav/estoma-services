package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.application.port.OperationDataMapper;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class JsonOperationDataMapper implements OperationDataMapper {
  private final ObjectMapper mapper;

  public JsonOperationDataMapper(ObjectMapper mapper) {
    this.mapper = mapper;
  }

  @Override
  public Object toHttpValue(String json) {
    return mapper.readValue(json, Object.class);
  }
}
