package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.contracts.MessageKind;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class CanonicalStringIdempotencyContractTest {

  @Test
  void decodesAndValidatesANonUuidFunctionalIdempotencyKeyFromTheBff() {
    String key = "communications:request:" + UUID.randomUUID();
    String json = """
        {
          "messageId":"%s",
          "messageType":"RequestCommunication",
          "messageVersion":1,
          "messageKind":"COMMAND",
          "producer":"bff",
          "occurredAt":"2026-08-23T18:00:00Z",
          "correlationId":"%s",
          "operationId":"%s",
          "idempotencyKey":"%s",
          "actor":{"type":"SYSTEM","service":"bff"},
          "payload":{}
        }
        """.formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), key);

    EnvelopeJsonCodec codec = new EnvelopeJsonCodec(new ObjectMapper());
    var envelope = codec.decode(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));

    assertThat(envelope.messageKind()).isEqualTo(MessageKind.COMMAND);
    assertThat(envelope.idempotencyKey()).isEqualTo(key);
    new EnvelopeValidator().validateCommand(envelope);
  }
}
