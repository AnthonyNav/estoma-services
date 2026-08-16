package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.command.ApplicationResult;
import com.estoma.authorization.application.port.CommandIdempotencyRepository.IdempotencyRecord;
import com.estoma.authorization.application.port.StoredApplicationResultDecoder;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
class JacksonStoredApplicationResultDecoder implements StoredApplicationResultDecoder {

  private final ObjectMapper mapper = new ObjectMapper();

  @Override
  public ApplicationResult decode(IdempotencyRecord record) {
    ApplicationResult.Status status = ApplicationResult.Status.valueOf(record.resultStatus());
    if (status == ApplicationResult.Status.SUCCEEDED) {
      return new ApplicationResult(status, record.resultDataJson(), null);
    }
    String details = null;
    if (record.errorDetailsJson() != null) {
      @SuppressWarnings("unchecked")
      Map<String, Object> decoded = mapper.readValue(record.errorDetailsJson(), Map.class);
      Object value = decoded.get("details");
      details = value == null ? null : value.toString();
    }
    return new ApplicationResult(
        status,
        record.resultDataJson(),
        record.errorCode() == null ? null : new ApplicationResult.Error(record.errorCode(), details));
  }
}
