package com.estoma.authentication.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authentication.application.AccountStatusSnapshotQueryService;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessageKind;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import com.estoma.authentication.contracts.Actor;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class EnvelopeValidatorTest {

  private final EnvelopeValidator validator = new EnvelopeValidator();
  private final QueryEnvelopeValidator queryValidator = new QueryEnvelopeValidator();
  private final ObjectMapper mapper = new ObjectMapper();

  @Test
  void rejectsAnOperationOutsideTheAuthenticationCommandContract() {
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), "DeleteAccount", 1, MessageKind.COMMAND, "bff", Instant.now(), UUID.randomUUID(), null,
        UUID.randomUUID(), "test", UUID.randomUUID(), 1, null, null);

    assertThatThrownBy(() -> validator.validateCommand(envelope)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void requiresAnIdempotencyKeyForEveryV1Command() {
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName(), 1, MessageKind.COMMAND, "bff",
        Instant.now(), UUID.randomUUID(), null, UUID.randomUUID(), null, null, null, null, null);

    assertThatThrownBy(() -> validator.validateCommand(envelope)).isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void requiresAnIdempotencyKeyForTemporaryPasswordRpc() {
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD.wireName(), 1, MessageKind.RPC_REQUEST, "bff",
        Instant.now(), UUID.randomUUID(), null, null, null, UUID.randomUUID(), null,
        new Actor("ACCOUNT", UUID.randomUUID(), null, "GLOBAL", null),
        mapper.createObjectNode());

    assertThatThrownBy(() -> validator.validateRpc(envelope, "amq.rabbitmq.reply-to"))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void acceptsAValidTemporaryPasswordRpcEnvelope() {
    MessageEnvelope envelope = new MessageEnvelope(
        UUID.randomUUID(), AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD.wireName(), 1, MessageKind.RPC_REQUEST, "bff",
        Instant.now(), UUID.randomUUID(), null, null, "idempotency-key", UUID.randomUUID(), null,
        new Actor("ACCOUNT", UUID.randomUUID(), null, "GLOBAL", null), mapper.createObjectNode());

    envelope = new MessageEnvelope(
        envelope.messageId(), envelope.messageType(), envelope.messageVersion(), envelope.messageKind(), envelope.producer(), envelope.occurredAt(),
        envelope.correlationId(), envelope.causationId(), envelope.operationId(), envelope.idempotencyKey(), envelope.aggregateId(), envelope.aggregateVersion(),
        new Actor("ACCOUNT", envelope.aggregateId(), null, "GLOBAL", null), envelope.payload());

    validator.validateRpc(envelope, "amq.rabbitmq.reply-to");
  }

  @Test
  void acceptsUsersPersonEventWhenOwnerAndAggregateMatch() {
    UUID personId = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("personId", personId.toString());
    payload.put("status", "ACTIVE");

    assertThatCode(() -> validator.validateEvent(event("PersonCreated", "users", personId, payload)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsPersonEventSpoofedByAnotherProducer() {
    UUID personId = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("personId", personId.toString());

    assertThatThrownBy(() -> validator.validateEvent(event("PersonCreated", "academic", personId, payload)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void rejectsPersonEventWhoseEnvelopeAggregateDiffersFromPayload() {
    var payload = mapper.createObjectNode();
    payload.put("personId", UUID.randomUUID().toString());

    assertThatThrownBy(() -> validator.validateEvent(event("PersonDeactivated", "users", UUID.randomUUID(), payload)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void studentNumberCorrectionMustBeOwnedByAcademicProfileAggregate() {
    UUID profileId = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("accountId", UUID.randomUUID().toString());
    payload.put("studentAcademicProfileId", profileId.toString());
    payload.put("studentNumberVersion", 2);
    payload.put("previousStudentNumber", "A1");
    payload.put("newStudentNumber", "A2");

    assertThatCode(() -> validator.validateEvent(event("StudentNumberCorrected", "academic", profileId, payload)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validateEvent(event("StudentNumberCorrected", "users", profileId, payload)))
        .isInstanceOf(InvalidMessageException.class);
  }

  @Test
  void acceptsTheBffAccountPresentationRecoveryQuery() {
    UUID accountId = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("accountId", accountId.toString());

    assertThatCode(() -> queryValidator.validate(query(
        AccountStatusSnapshotQueryService.GET_PRESENTATION, "bff", payload)))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsAnAuthorizationCallerForTheBffPresentationSnapshot() {
    UUID accountId = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("accountId", accountId.toString());

    assertThatThrownBy(() -> queryValidator.validate(query(
        AccountStatusSnapshotQueryService.GET_PRESENTATION, "authorization", payload)))
        .isInstanceOf(InvalidMessageException.class);
  }

  private MessageEnvelope event(String type, String producer, UUID aggregateId, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.EVENT, producer, Instant.now(), UUID.randomUUID(), UUID.randomUUID(),
        null, null, aggregateId, 1, null, payload);
  }

  private MessageEnvelope query(String type, String requester, tools.jackson.databind.JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(), type, 1, MessageKind.QUERY, requester, Instant.now(), UUID.randomUUID(), null,
        UUID.randomUUID(), null, null, null, new Actor("SYSTEM", null, requester, null, null), payload);
  }
}
