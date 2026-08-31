package com.estoma.users.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class QueryEnvelopeValidatorTest {

  private final QueryEnvelopeValidator validator =
      new QueryEnvelopeValidator(
          new MessagingProperties(
              Map.of(),
              Map.of(
                  "academic", Set.of(MessageTypes.RESOLVE_PERSON_BY_INSTITUTIONAL_EMAIL),
                  "bff", Set.of(MessageTypes.LIST_PERSON_PRESENTATION_SNAPSHOTS_PAGE)),
              true,
              3,
              3,
              Duration.ofSeconds(5)));
  private final tools.jackson.databind.ObjectMapper mapper = JsonMapper.builder().build();

  @Test
  void acceptsAWellFormedResolveQuery() {
    assertThatCode(() -> validator.validate(validQuery())).doesNotThrowAnyException();
  }

  @Test
  void rejectsNonQueryMessageKind() {
    MessageEnvelope wrongKind = withKind(validQuery(), MessageKind.COMMAND);

    assertThatThrownBy(() -> validator.validate(wrongKind)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsUnsupportedMessageVersion() {
    MessageEnvelope wrongVersion = withVersion(validQuery(), 99);

    assertThatThrownBy(() -> validator.validate(wrongVersion)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsUnrecognizedMessageType() {
    MessageEnvelope wrongType = withMessageType(validQuery(), "SomeUnknownQuery");

    assertThatThrownBy(() -> validator.validate(wrongType)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsProducerNotAuthorizedForThisQuery() {
    MessageEnvelope wrongProducer = withProducer(validQuery(), "bff");

    assertThatThrownBy(() -> validator.validate(wrongProducer)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingOperationId() {
    MessageEnvelope missingOperationId = withOperationId(validQuery(), null);

    assertThatThrownBy(() -> validator.validate(missingOperationId)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingActor() {
    MessageEnvelope missingActor = withActor(validQuery(), null);

    assertThatThrownBy(() -> validator.validate(missingActor)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsAnAccountActorOnAQuery() {
    // A Query is issued by a service acting on its own behalf -- an
    // ACCOUNT (human) actor is structurally wrong here, not just
    // unauthorized.
    MessageEnvelope accountActor = withActor(validQuery(), new Actor.Account(UUID.randomUUID()));

    assertThatThrownBy(() -> validator.validate(accountActor)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsSystemActorWithoutService() {
    assertThatThrownBy(() -> validator.validate(withActor(validQuery(), new Actor.System(null))))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void acceptsTheBffPersonPresentationRecoveryQuery() {
    MessageEnvelope bffQuery = new MessageEnvelope(
        UUID.randomUUID(), MessageTypes.LIST_PERSON_PRESENTATION_SNAPSHOTS_PAGE, 1, MessageKind.QUERY,
        "bff", Instant.now(), UUID.randomUUID(), null, UUID.randomUUID(), null, null, null,
        new Actor.System("bff"), mapper.valueToTree(Map.of("limit", 200)));

    assertThatCode(() -> validator.validate(bffQuery)).doesNotThrowAnyException();
  }

  @Test
  void rejectsTheBffPresentationQueryFromAnotherProducer() {
    MessageEnvelope academicQuery = new MessageEnvelope(
        UUID.randomUUID(), MessageTypes.LIST_PERSON_PRESENTATION_SNAPSHOTS_PAGE, 1, MessageKind.QUERY,
        "academic", Instant.now(), UUID.randomUUID(), null, UUID.randomUUID(), null, null, null,
        new Actor.System("academic"), mapper.valueToTree(Map.of("limit", 200)));

    assertThatThrownBy(() -> validator.validate(academicQuery)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsSystemActorWithBlankService() {
    assertThatThrownBy(() -> validator.validate(withActor(validQuery(), new Actor.System("   "))))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsSystemActorFromAnotherService() {
    assertThatThrownBy(() -> validator.validate(withActor(validQuery(), new Actor.System("another-service"))))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void doesNotRequireAnIdempotencyKey() {
    MessageEnvelope noIdempotencyKey = validQuery(); // idempotencyKey is already null in the fixture

    assertThatCode(() -> validator.validate(noIdempotencyKey)).doesNotThrowAnyException();
  }

  @Test
  void rejectsMissingMessageId() {
    assertThatThrownBy(() -> validator.validate(withMessageId(validQuery(), null)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingCorrelationId() {
    assertThatThrownBy(() -> validator.validate(withCorrelationId(validQuery(), null)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingOccurredAt() {
    assertThatThrownBy(() -> validator.validate(withOccurredAt(validQuery(), null)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingPayload() {
    assertThatThrownBy(() -> validator.validate(withPayload(validQuery(), null)))
        .isInstanceOf(InvalidMessageException.class);
  }

  private MessageEnvelope validQuery() {
    return new MessageEnvelope(
        UUID.randomUUID(),
        "ResolvePersonByInstitutionalEmail",
        1,
        MessageKind.QUERY,
        "academic",
        Instant.now(),
        UUID.randomUUID(),
        null,
        UUID.randomUUID(),
        null,
        null,
        null,
        new Actor.System("academic"),
        mapper.valueToTree(java.util.Map.of("institutionalEmail", "juan@universidad.mx")));
  }

  private MessageEnvelope withKind(MessageEnvelope e, MessageKind kind) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), kind, e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
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

  private MessageEnvelope withOccurredAt(MessageEnvelope e, Instant occurredAt) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), occurredAt,
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withPayload(MessageEnvelope e, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.idempotencyKey(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), payload);
  }
}
