package com.estoma.authorization.application.support;

import com.estoma.authorization.application.command.ApplicationResult;
import com.estoma.authorization.application.command.AuthorizationCommand;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.OutboundResultIntent;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class ResultIntentFactory {

  private ResultIntentFactory() {}

  public static OutboundResultIntent create(
      CommandMetadata metadata,
      AuthorizationCommand command,
      ApplicationResult result,
      UUID aggregateId,
      Integer aggregateVersion,
      Instant occurredAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", result.status());
    payload.put("data", result.dataJson() == null ? null : new RawJson(result.dataJson()));
    if (result.error() == null) {
      payload.put("error", null);
    } else {
      Map<String, Object> error = new LinkedHashMap<>();
      error.put("code", result.error().code());
      error.put("details", result.error().details());
      payload.put("error", error);
    }
    return new OutboundResultIntent(
        UUID.randomUUID(),
        command.operationType() + "Result",
        1,
        "authorization",
        metadata.producer(),
        metadata.operationId(),
        metadata.idempotencyKey(),
        metadata.actor(),
        aggregateId,
        aggregateVersion,
        metadata.correlationId(),
        metadata.messageId(),
        rawObjectJson(payload),
        occurredAt);
  }

  private static String rawObjectJson(Map<String, Object> values) {
    StringBuilder json = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, Object> entry : values.entrySet()) {
      if (!first) {
        json.append(',');
      }
      first = false;
      json.append(CanonicalJson.write(entry.getKey())).append(':');
      if (entry.getValue() instanceof RawJson raw) {
        json.append(raw.value());
      } else {
        json.append(CanonicalJson.write(entry.getValue()));
      }
    }
    return json.append('}').toString();
  }

  private record RawJson(String value) {}
}
