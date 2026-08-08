package com.estoma.users.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Exercises the real producer/operation matrix this service ships with
 * (application.yml {@code estoma.messaging.command-producers}, mirrored
 * here): a producer being technically trusted does not by itself
 * authorize every operation (commands.md §8) — {@code academic} (the
 * {@code student-import-process}) may only create/update Persons, never
 * activate or deactivate them.
 */
class ProducerOperationMatrixTest {

  private static final Map<String, Set<String>> PRODUCTION_SHAPED_MATRIX =
      Map.of(
          "bff", Set.of("CreatePerson", "UpdatePerson", "ActivatePerson", "DeactivatePerson"),
          "academic", Set.of("CreatePerson", "UpdatePerson"));

  private final EnvelopeValidator validator =
      new EnvelopeValidator(
          new MessagingProperties(PRODUCTION_SHAPED_MATRIX, Map.of(), true, 3, 3, Duration.ofSeconds(5)));
  private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void bffMayCreatePerson() {
    assertThatCode(() -> validator.validate(command("bff", "CreatePerson"))).doesNotThrowAnyException();
  }

  @Test
  void bffMayActivatePerson() {
    assertThatCode(() -> validator.validate(command("bff", "ActivatePerson"))).doesNotThrowAnyException();
  }

  @Test
  void bffMayDeactivatePerson() {
    assertThatCode(() -> validator.validate(command("bff", "DeactivatePerson"))).doesNotThrowAnyException();
  }

  @Test
  void academicMayCreatePerson() {
    assertThatCode(() -> validator.validate(command("academic", "CreatePerson"))).doesNotThrowAnyException();
  }

  @Test
  void academicMayUpdatePerson() {
    assertThatCode(() -> validator.validate(command("academic", "UpdatePerson"))).doesNotThrowAnyException();
  }

  @Test
  void academicMayNotActivatePerson() {
    assertThatThrownBy(() -> validator.validate(command("academic", "ActivatePerson")))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void academicMayNotDeactivatePerson() {
    assertThatThrownBy(() -> validator.validate(command("academic", "DeactivatePerson")))
        .isInstanceOf(InvalidMessageException.class);
  }

  private MessageEnvelope command(String producer, String messageType) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        messageType,
        1,
        MessageKind.COMMAND,
        producer,
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new Actor.Account(UUID.randomUUID()),
        mapper.valueToTree(java.util.Map.of()));
  }
}
