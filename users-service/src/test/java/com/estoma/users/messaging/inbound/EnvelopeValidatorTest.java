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

class EnvelopeValidatorTest {

  private final EnvelopeValidator validator =
      new EnvelopeValidator(
          new MessagingProperties(Map.of("bff", Set.of("CreatePerson")), true, 3, Duration.ofSeconds(5)));
  private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void acceptsAWellFormedCreatePersonCommand() {
    assertThatCode(() -> validator.validate(validCommand())).doesNotThrowAnyException();
  }

  @Test
  void rejectsNonCommandMessageKind() {
    MessageEnvelope wrongKind = withKind(validCommand(), MessageKind.EVENT);

    assertThatThrownBy(() -> validator.validate(wrongKind)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsQueryMessageKindOnTheCommandsQueue() {
    MessageEnvelope query = withKind(validCommand(), MessageKind.QUERY);

    assertThatThrownBy(() -> validator.validate(query)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsUnsupportedMessageVersion() {
    MessageEnvelope wrongVersion = withVersion(validCommand(), 99);

    assertThatThrownBy(() -> validator.validate(wrongVersion)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsUnrecognizedMessageType() {
    MessageEnvelope wrongType = withMessageType(validCommand(), "SomeUnknownCommand");

    assertThatThrownBy(() -> validator.validate(wrongType)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsProducerNotOnTheMatrix() {
    MessageEnvelope wrongProducer = withProducer(validCommand(), "some-untrusted-caller");

    assertThatThrownBy(() -> validator.validate(wrongProducer)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsAProducerAuthorizedForADifferentOperation() {
    // "bff" is only wired up for CreatePerson in this test's fixture
    // (see the validator field) — a producer being trusted at all does
    // not imply it may issue every operation.
    MessageEnvelope wrongOperation = withMessageType(validCommand(), "ActivatePerson");

    assertThatThrownBy(() -> validator.validate(wrongOperation)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingOperationId() {
    MessageEnvelope missingOperationId = withOperationId(validCommand(), null);

    assertThatThrownBy(() -> validator.validate(missingOperationId))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingIdempotencyKey() {
    MessageEnvelope missingIdempotencyKey = withIdempotencyKey(validCommand(), null);

    assertThatThrownBy(() -> validator.validate(missingIdempotencyKey))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsBlankIdempotencyKey() {
    MessageEnvelope blankIdempotencyKey = withIdempotencyKey(validCommand(), "   ");

    assertThatThrownBy(() -> validator.validate(blankIdempotencyKey))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingActor() {
    MessageEnvelope missingActor = withActor(validCommand(), null);

    assertThatThrownBy(() -> validator.validate(missingActor)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsActorWithoutAccountId() {
    MessageEnvelope actorWithoutAccountId = withActor(validCommand(), new Actor.Account(null));

    assertThatThrownBy(() -> validator.validate(actorWithoutAccountId))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsASystemActorOnACommand() {
    // Commands require a human ACCOUNT actor in V1; a technical SYSTEM
    // actor is reserved for Query (QueryEnvelopeValidator).
    MessageEnvelope systemActor = withActor(validCommand(), new Actor.System("academic"));

    assertThatThrownBy(() -> validator.validate(systemActor)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingMessageId() {
    assertThatThrownBy(() -> validator.validate(withMessageId(validCommand(), null)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingCorrelationId() {
    assertThatThrownBy(() -> validator.validate(withCorrelationId(validCommand(), null)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void allowsASecondConfiguredProducer() {
    EnvelopeValidator twoProducerValidator =
        new EnvelopeValidator(
            new MessagingProperties(
                Map.of("bff", Set.of("CreatePerson"), "academic", Set.of("CreatePerson")),
                true,
                3,
                Duration.ofSeconds(5)));

    assertThatCode(() -> twoProducerValidator.validate(withProducer(validCommand(), "academic")))
        .doesNotThrowAnyException();
  }

  private MessageEnvelope validCommand() {
    return new MessageEnvelope(
        UUID.randomUUID(),
        "CreatePerson",
        1,
        MessageKind.COMMAND,
        "bff",
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

  private MessageEnvelope withKind(MessageEnvelope e, MessageKind kind) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), kind, e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withIdempotencyKey(MessageEnvelope e, String idempotencyKey) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), idempotencyKey, e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withVersion(MessageEnvelope e, int version) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), version, e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withMessageType(MessageEnvelope e, String messageType) {
    return new MessageEnvelope(
        e.messageId(), messageType, e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withProducer(MessageEnvelope e, String producer) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), producer, e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withOperationId(MessageEnvelope e, UUID operationId) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), operationId, e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withMessageId(MessageEnvelope e, UUID messageId) {
    return new MessageEnvelope(
        messageId, e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withCorrelationId(MessageEnvelope e, UUID correlationId) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        correlationId, e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withActor(MessageEnvelope e, Actor actor) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), actor, e.payload());
  }
}
