package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.query.IntegrationQueryMetadata;
import com.estoma.washexecution.application.query.WashExecutionIntegrationQuery;
import com.estoma.washexecution.contracts.MessageEnvelope;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class IntegrationQueryMessageMapper {
  public IntegrationQueryMetadata metadata(MessageEnvelope envelope) {
    var actor = envelope.actor();
    return new IntegrationQueryMetadata(
        envelope.messageId(),
        envelope.messageType(),
        envelope.producer(),
        envelope.correlationId(),
        envelope.operationId(),
        actor == null ? null : new ActorContext(actor.type(), actor.accountId(), actor.service()));
  }

  public WashExecutionIntegrationQuery query(MessageEnvelope envelope) {
    JsonNode payload = envelope.payload();
    return switch (envelope.messageType()) {
      case "GetWashExecutionIntegrationContext" ->
          new WashExecutionIntegrationQuery.GetContext(uuid(payload, "appointmentId", "INVALID_APPOINTMENT_ID"));
      case "ListWashExecutionIntegrationContextsPage" ->
          new WashExecutionIntegrationQuery.ListPage(
              serviceDate(payload),
              optionalInt(payload, "pageSize"),
              optionalPageToken(payload));
      default -> throw rejected("UNSUPPORTED_QUERY");
    };
  }

  private static LocalDate serviceDate(JsonNode payload) {
    try {
      return LocalDate.parse(text(payload, "serviceDate", "INVALID_SERVICE_DATE"));
    } catch (DateTimeParseException exception) {
      throw rejected("INVALID_SERVICE_DATE");
    }
  }

  private static String optionalPageToken(JsonNode payload) {
    String token = optionalText(payload, "pageToken");
    if (token == null || token.isBlank()) return null;
    try {
      UUID.fromString(token);
      return token;
    } catch (IllegalArgumentException exception) {
      throw rejected("INVALID_PAGE_TOKEN");
    }
  }

  private static String text(JsonNode payload, String field, String error) {
    String value = optionalText(payload, field);
    if (value == null || value.isBlank()) throw rejected(error);
    return value;
  }

  private static String optionalText(JsonNode payload, String field) {
    JsonNode value = payload == null ? null : payload.get(field);
    return value == null || value.isNull() ? null : value.asText();
  }

  private static UUID uuid(JsonNode payload, String field, String error) {
    try {
      return UUID.fromString(text(payload, field, error));
    } catch (IllegalArgumentException exception) {
      throw rejected(error);
    }
  }

  private static Integer optionalInt(JsonNode payload, String field) {
    JsonNode value = payload == null ? null : payload.get(field);
    if (value == null || value.isNull()) return null;
    if (!value.isIntegralNumber()) throw rejected("INVALID_PAGE_SIZE");
    return value.asInt();
  }

  private static IllegalArgumentException rejected(String code) {
    return new IllegalArgumentException(code);
  }
}
