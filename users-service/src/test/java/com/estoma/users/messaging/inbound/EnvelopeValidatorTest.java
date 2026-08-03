package com.estoma.users.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class EnvelopeValidatorTest {

  private final EnvelopeValidator validator = new EnvelopeValidator(new MessagingProperties(Set.of("bff")));
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
  void rejectsWrongTargetService() {
    MessageEnvelope wrongTarget = withTargetService(validCommand(), "authentication-service");

    assertThatThrownBy(() -> validator.validate(wrongTarget)).isInstanceOf(InvalidMessageException.class);
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
  void rejectsProducerNotOnTheAllowList() {
    MessageEnvelope wrongProducer = withProducer(validCommand(), "some-untrusted-caller");

    assertThatThrownBy(() -> validator.validate(wrongProducer)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsMissingOperationId() {
    MessageEnvelope missingOperationId = withOperationId(validCommand(), null);

    assertThatThrownBy(() -> validator.validate(missingOperationId))
        .isInstanceOf(InvalidMessageException.class);
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
        new EnvelopeValidator(new MessagingProperties(Set.of("bff", "student-import-process")));

    assertThatCode(
            () -> twoProducerValidator.validate(withProducer(validCommand(), "student-import-process")))
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
        "users-service",
        null,
        null,
        new Actor(UUID.randomUUID()),
        mapper.valueToTree(java.util.Map.of()));
  }

  private MessageEnvelope withKind(MessageEnvelope e, MessageKind kind) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), kind, e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.targetService(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withTargetService(MessageEnvelope e, String targetService) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), targetService, e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withVersion(MessageEnvelope e, int version) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), version, e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.targetService(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withMessageType(MessageEnvelope e, String messageType) {
    return new MessageEnvelope(
        e.messageId(), messageType, e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.targetService(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withProducer(MessageEnvelope e, String producer) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), producer, e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.targetService(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withOperationId(MessageEnvelope e, UUID operationId) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), operationId, e.targetService(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withMessageId(MessageEnvelope e, UUID messageId) {
    return new MessageEnvelope(
        messageId, e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        e.correlationId(), e.causationId(), e.operationId(), e.targetService(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }

  private MessageEnvelope withCorrelationId(MessageEnvelope e, UUID correlationId) {
    return new MessageEnvelope(
        e.messageId(), e.messageType(), e.messageVersion(), e.messageKind(), e.producer(), e.occurredAt(),
        correlationId, e.causationId(), e.operationId(), e.targetService(), e.aggregateId(),
        e.aggregateVersion(), e.actor(), e.payload());
  }
}
