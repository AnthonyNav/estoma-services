package com.estoma.users.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.event.PersonCreatedPayload;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Pure unit test (no Spring context, no containers): constructs its own
 * {@link tools.jackson.databind.ObjectMapper} the same way Spring Boot's
 * autoconfigured one would behave for our purposes, to verify the wire
 * contract in docs/system-contract.md §2 independent of DI wiring.
 */
class EnvelopeJsonCodecTest {

  private final EnvelopeJsonCodec codec = new EnvelopeJsonCodec(JsonMapper.builder().build());

  @Test
  void roundTripsAllEnvelopeFieldsIncludingNullableOnes() {
    UUID personId = UUID.randomUUID();
    MessageEnvelope original =
        new MessageEnvelope(
            UUID.randomUUID(),
            "CreatePerson",
            1,
            MessageKind.COMMAND,
            "bff",
            Instant.parse("2026-08-03T12:00:00Z"),
            UUID.randomUUID(),
            null, // causationId: absent on a client-originated command
            UUID.randomUUID(),
            UUID.randomUUID().toString(),
            personId,
            null,
            new Actor.Account(UUID.randomUUID()),
            codec.toJsonNode(
                new PersonCreatedPayload(
                    personId, "Ada", "Lovelace", null, "ada@example.edu", null, "ACTIVE", 1, Instant.now())));

    byte[] encoded = codec.encode(original);
    MessageEnvelope decoded = codec.decode(encoded);

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void serializesFieldNamesAsCamelCase() {
    MessageEnvelope envelope = minimalCommandEnvelope();

    String json = new String(codec.encode(envelope), StandardCharsets.UTF_8);

    assertThat(json).contains("\"messageId\"");
    assertThat(json).contains("\"messageType\"");
    assertThat(json).contains("\"correlationId\"");
    assertThat(json).contains("\"idempotencyKey\"");
  }

  @Test
  void serializesActorWithATypeDiscriminator() {
    MessageEnvelope envelope = minimalCommandEnvelope();

    String json = new String(codec.encode(envelope), StandardCharsets.UTF_8);

    assertThat(json).contains("\"type\":\"ACCOUNT\"");
    assertThat(json).contains("\"accountId\"");
  }

  @Test
  void roundTripsASystemActor() {
    MessageEnvelope original =
        new MessageEnvelope(
            UUID.randomUUID(),
            "ResolvePersonByInstitutionalEmail",
            1,
            MessageKind.QUERY,
            "academic",
            Instant.parse("2026-08-03T12:00:00Z"),
            UUID.randomUUID(),
            null,
            UUID.randomUUID(),
            null,
            null,
            null,
            new Actor.System("academic"),
            codec.toJsonNode(java.util.Map.of("institutionalEmail", "juan@universidad.mx")));

    MessageEnvelope decoded = codec.decode(codec.encode(original));

    assertThat(decoded.actor()).isEqualTo(new Actor.System("academic"));
    assertThat(decoded.actor().accountId()).isNull();
  }

  @Test
  void serializesOccurredAtAsIso8601Utc() {
    MessageEnvelope envelope = minimalCommandEnvelope();

    String json = new String(codec.encode(envelope), StandardCharsets.UTF_8);

    assertThat(json).contains("2026-08-03T12:00:00Z");
  }

  @Test
  void toleratesUnknownFieldsOnDeserialization() {
    String jsonWithExtraField =
        """
        {
          "messageId": "%s",
          "messageType": "CreatePerson",
          "messageVersion": 1,
          "messageKind": "COMMAND",
          "producer": "bff",
          "occurredAt": "2026-08-03T12:00:00Z",
          "correlationId": "%s",
          "operationId": "%s",
          "idempotencyKey": "%s",
          "payload": {},
          "aFieldFromAFutureContractVersion": "should be ignored"
        }
        """
            .formatted(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());

    MessageEnvelope decoded = codec.decode(jsonWithExtraField.getBytes(StandardCharsets.UTF_8));

    assertThat(decoded.messageType()).isEqualTo("CreatePerson");
  }

  @Test
  void nullMaternalSurnameAndPhoneRoundTripAsNullNotAbsent() {
    PersonCreatedPayload payload =
        new PersonCreatedPayload(
            UUID.randomUUID(), "Ada", "Lovelace", null, "ada@example.edu", null, "ACTIVE", 1, Instant.now());

    var node = codec.toJsonNode(payload);
    PersonCreatedPayload roundTripped =
        new tools.jackson.databind.json.JsonMapper()
            .treeToValue(node, PersonCreatedPayload.class);

    assertThat(roundTripped.maternalSurname()).isNull();
    assertThat(roundTripped.phone()).isNull();
  }

  private MessageEnvelope minimalCommandEnvelope() {
    UUID personId = UUID.randomUUID();
    return new MessageEnvelope(
        UUID.randomUUID(),
        "CreatePerson",
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.parse("2026-08-03T12:00:00Z"),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        personId,
        null,
        new Actor.Account(UUID.randomUUID()),
        codec.toJsonNode(java.util.Map.of("personId", personId.toString())));
  }
}
