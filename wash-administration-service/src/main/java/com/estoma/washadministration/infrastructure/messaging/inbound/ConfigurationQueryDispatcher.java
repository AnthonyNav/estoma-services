package com.estoma.washadministration.infrastructure.messaging.inbound;

import com.estoma.washadministration.application.port.JsonCodec;
import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.OutboxRepository.OutboxMessage;
import com.estoma.washadministration.application.query.GetWashAdministrationConfiguration;
import com.estoma.washadministration.application.query.GetWashAdministrationConfiguration.QueryRejectedException;
import com.estoma.washadministration.contracts.MessageEnvelope;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class ConfigurationQueryDispatcher {

  private static final String PRODUCER = "wash-administration";

  private final GetWashAdministrationConfiguration query;
  private final OutboxRepository outbox;
  private final JsonCodec json;

  public ConfigurationQueryDispatcher(
      GetWashAdministrationConfiguration query, OutboxRepository outbox, JsonCodec json) {
    this.query = query;
    this.outbox = outbox;
    this.json = json;
  }

  /** Snapshot read and correlated Result Outbox share one REPEATABLE_READ transaction. */
  @Transactional(isolation = Isolation.REPEATABLE_READ)
  public void dispatch(MessageEnvelope envelope, java.time.LocalDate referenceDate) {
    Instant now = Instant.now();
    try {
      GetWashAdministrationConfiguration.Snapshot snapshot =
          query.execute(envelope.producer(), referenceDate);
      appendResult(envelope, "SUCCEEDED", Map.of("data", snapshot), null, now);
    } catch (QueryRejectedException exception) {
      appendResult(
          envelope,
          "REJECTED",
          null,
          Map.of("code", exception.code()),
          now);
    }
  }

  private void appendResult(
      MessageEnvelope request,
      String status,
      Map<String, Object> success,
      Map<String, Object> error,
      Instant occurredAt) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("status", status);
    if (success != null) {
      payload.putAll(success);
    }
    if (error != null) {
      payload.put("error", error);
    }
    outbox.append(
        new OutboxMessage(
            UUID.randomUUID(),
            "GetWashAdministrationConfigurationResult",
            1,
            "RESULT",
            PRODUCER,
            request.producer(),
            occurredAt,
            request.operationId(),
            null,
            null,
            null,
            request.correlationId(),
            request.messageId(),
            json.write(request.actor()),
            json.write(payload),
            null));
  }
}
