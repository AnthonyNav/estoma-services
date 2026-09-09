package com.estoma.platformbff.application.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.ClientOperationRepository;
import com.estoma.platformbff.application.port.IdempotencyRepository;
import com.estoma.platformbff.application.port.MessageSerializer;
import com.estoma.platformbff.application.port.OutboxRepository;
import com.estoma.platformbff.application.port.RequestFingerprintPort;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.CommandSubmission;
import com.estoma.platformbff.domain.idempotency.IdempotencyRecord;
import com.estoma.platformbff.domain.messaging.OutboxMessage;
import com.estoma.platformbff.domain.operation.ClientOperation;
import com.estoma.platformbff.infrastructure.messaging.MessageEnvelopeCodec;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@ExtendWith(MockitoExtension.class)
class CommandGatewayServiceTest {
  @Mock ClientOperationRepository operations;
  @Mock IdempotencyRepository idempotency;
  @Mock OutboxRepository outbox;
  @Mock MessageSerializer serializer;
  @Mock RequestFingerprintPort fingerprints;

  private CommandGatewayService gateway;

  @BeforeEach
  void setUp() {
    gateway = new CommandGatewayService(
        new CommandRegistry(), operations, idempotency, outbox, serializer, fingerprints);
    when(fingerprints.normalize(any(), any(), any(), any(), any(), any()))
        .thenReturn(new RequestFingerprintPort.NormalizedRequest("{\"firstName\":\"Ada\"}", "fingerprint"));
  }

  @Test
  void createsPendingOperationAndUnexpiredIdempotencyRecord() {
    UUID accountId = UUID.randomUUID();
    when(idempotency.findByLogicalScope(any(), any(), any())).thenReturn(Optional.empty());
    when(idempotency.insertIfAbsent(any())).thenReturn(true);
    when(serializer.write(any())).thenReturn("{}");

    var result = gateway.submit(new CommandSubmission(
        "create-person", "CreatePerson", "{\"firstName\":\"Ada\"}", "key-1",
        accountId, ActorContext.account(accountId, "GLOBAL", null), UUID.randomUUID(), null));

    assertThat(result.status()).isEqualTo("PENDING");
    var operation = ArgumentCaptor.forClass(ClientOperation.class);
    verify(operations).insert(operation.capture());
    assertThat(operation.getValue().expiresAt()).isNull();

    var record = ArgumentCaptor.forClass(IdempotencyRecord.class);
    verify(idempotency).insertIfAbsent(record.capture());
    assertThat(record.getValue().expiresAt()).isNull();

    verify(outbox).insert(any(OutboxMessage.class));
  }

  @Test
  void fingerprintsStableHttpIntentButPublishesServerEnrichedOwnerPayload() {
    UUID accountId = UUID.randomUUID();
    UUID generatedAggregateId = UUID.randomUUID();
    when(idempotency.findByLogicalScope(accountId, "wash-schedule-appointment", "key-1"))
        .thenReturn(Optional.empty());
    when(idempotency.insertIfAbsent(any())).thenReturn(true);
    when(serializer.write(any())).thenReturn("{}");
    when(fingerprints.normalize(any(), any(), any(), any(), any(), any()))
        .thenReturn(
            new RequestFingerprintPort.NormalizedRequest(
                "{\"appointmentId\":\"server-generated\"}", "owner-fingerprint"),
            new RequestFingerprintPort.NormalizedRequest(
                "{\"appointmentTimeSlotId\":\"stable\"}", "stable-fingerprint"));

    gateway.submit(new CommandSubmission(
        "wash-schedule-appointment", "ScheduleAppointment",
        "{\"appointmentId\":\"server-generated\"}",
        "{\"appointmentTimeSlotId\":\"stable\"}",
        "key-1", accountId, ActorContext.account(accountId, "GLOBAL", null),
        generatedAggregateId, null, null, null));

    var record = ArgumentCaptor.forClass(IdempotencyRecord.class);
    verify(idempotency).insertIfAbsent(record.capture());
    assertThat(record.getValue().requestFingerprint()).isEqualTo("stable-fingerprint");

    var message = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outbox).insert(message.capture());
    assertThat(message.getValue().aggregateId()).isEqualTo(generatedAggregateId);
    assertThat(message.getValue().payloadJson()).isEqualTo("{\"appointmentId\":\"server-generated\"}");
  }

  @Test
  void strictOwnerCommandsPublishExactAccountActorWhileFingerprintsRetainGlobalScope() {
    UUID accountId = UUID.randomUUID();
    when(idempotency.findByLogicalScope(any(), any(), any())).thenReturn(Optional.empty());
    when(idempotency.insertIfAbsent(any())).thenReturn(true);
    var codec = new MessageEnvelopeCodec(JsonMapper.builder().build());
    var realSerializerGateway = new CommandGatewayService(
        new CommandRegistry(), operations, idempotency, outbox, codec, fingerprints);
    ActorContext globalActor = ActorContext.account(accountId, "GLOBAL", null);

    realSerializerGateway.submit(new CommandSubmission(
        "admin.authorization.assign-role", "AssignAccountRole", "{}", UUID.randomUUID().toString(),
        accountId, globalActor, accountId, null));
    realSerializerGateway.submit(new CommandSubmission(
        "admin.authorization.grant-system-access", "GrantSystemAccess", "{}",
        UUID.randomUUID().toString(), accountId, globalActor, UUID.randomUUID(), null));
    realSerializerGateway.submit(new CommandSubmission(
        "admin.wash.calendars.create", "CreateServiceCalendar", "{}",
        UUID.randomUUID().toString(), accountId, globalActor, UUID.randomUUID(), null));
    realSerializerGateway.submit(new CommandSubmission(
        "admin.wash.time-slots.create", "CreateAppointmentTimeSlot", "{}",
        UUID.randomUUID().toString(), accountId, globalActor, UUID.randomUUID(), null));

    var messages = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outbox, times(4)).insert(messages.capture());
    assertThat(messages.getAllValues()).allSatisfy(message -> {
      JsonNode actor = codec.read(message.actorJson(), JsonNode.class);
      assertThat(actor.size()).isEqualTo(2);
      assertThat(actor.path("type").asString()).isEqualTo("ACCOUNT");
      assertThat(actor.path("accountId").asString()).isEqualTo(accountId.toString());
      assertThat(actor.has("scope")).isFalse();
    });
    var fingerprintActors = ArgumentCaptor.forClass(ActorContext.class);
    verify(fingerprints, times(8)).normalize(
        any(), any(), any(), any(), any(), fingerprintActors.capture());
    assertThat(fingerprintActors.getAllValues()).containsOnly(globalActor);
  }

  @Test
  void authorizationRetryStillFingerprintsTheGlobalActorAndDoesNotRepublish() {
    UUID accountId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    var existing = new IdempotencyRecord(
        "scope", accountId, "admin.authorization.assign-role", UUID.randomUUID().toString(),
        "fingerprint", operationId, Instant.now(), null);
    var operation = new ClientOperation(
        operationId, accountId, "admin.authorization.assign-role", "AssignAccountRole",
        "authorization", "authorization", UUID.randomUUID(), existing.idempotencyKey(),
        com.estoma.platformbff.domain.operation.OperationStatus.PENDING,
        null, null, null, Instant.now(), Instant.now(), null, null);
    when(idempotency.findByLogicalScope(
        accountId, existing.publicOperation(), existing.idempotencyKey())).thenReturn(Optional.of(existing));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));
    var codec = new MessageEnvelopeCodec(JsonMapper.builder().build());
    var realSerializerGateway = new CommandGatewayService(
        new CommandRegistry(), operations, idempotency, outbox, codec, fingerprints);
    ActorContext globalActor = ActorContext.account(accountId, "GLOBAL", null);

    var result = realSerializerGateway.submit(new CommandSubmission(
        existing.publicOperation(), "AssignAccountRole", "{}", existing.idempotencyKey(),
        accountId, globalActor, accountId, null));

    assertThat(result.operationId()).isEqualTo(operationId);
    assertThat(result.reused()).isTrue();
    var fingerprintActors = ArgumentCaptor.forClass(ActorContext.class);
    verify(fingerprints, times(2)).normalize(
        any(), any(), any(), any(), any(), fingerprintActors.capture());
    assertThat(fingerprintActors.getAllValues()).containsOnly(globalActor);
    verify(outbox, never()).insert(any());
  }

  @Test
  void authenticationCommandKeepsGlobalScopeInThePublishedActor() {
    UUID accountId = UUID.randomUUID();
    when(idempotency.findByLogicalScope(any(), any(), any())).thenReturn(Optional.empty());
    when(idempotency.insertIfAbsent(any())).thenReturn(true);
    var codec = new MessageEnvelopeCodec(JsonMapper.builder().build());
    var realSerializerGateway = new CommandGatewayService(
        new CommandRegistry(), operations, idempotency, outbox, codec, fingerprints);

    realSerializerGateway.submit(new CommandSubmission(
        "admin.accounts.create", "CreateAccount", "{}", UUID.randomUUID().toString(),
        accountId, ActorContext.account(accountId, "GLOBAL", null), accountId, null));

    var message = ArgumentCaptor.forClass(OutboxMessage.class);
    verify(outbox).insert(message.capture());
    JsonNode actor = codec.read(message.getValue().actorJson(), JsonNode.class);
    assertThat(actor.path("type").asString()).isEqualTo("ACCOUNT");
    assertThat(actor.path("accountId").asString()).isEqualTo(accountId.toString());
    assertThat(actor.path("scope").asString()).isEqualTo("GLOBAL");
  }

  @Test
  void passwordRecoveryAllowsOnlyCanonicalAnonymousActor() {
    when(idempotency.findByLogicalScope(any(), any(), any())).thenReturn(Optional.empty());
    when(idempotency.insertIfAbsent(any())).thenReturn(true);
    when(serializer.write(any())).thenReturn("{}");

    gateway.submit(new CommandSubmission(
        "request-password-recovery", "RequestPasswordRecovery", "{\"loginType\":\"MATRICULA\"}",
        "public-key", null, ActorContext.anonymous(), null, null));

    verify(outbox).insert(any());

    assertThatThrownBy(() -> gateway.submit(new CommandSubmission(
        "create-person", "CreatePerson", "{}", "bad-key", null,
        ActorContext.anonymous(), UUID.randomUUID(), null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reusesExistingOperationForSameLogicalScopeAndFingerprint() {
    UUID accountId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    var existing = new IdempotencyRecord(
        "scope", accountId, "create-person", "key-1", "fingerprint",
        operationId, Instant.now(), null);
    var operation = new ClientOperation(
        operationId, accountId, "create-person", "CreatePerson", "users", "users",
        UUID.randomUUID(), "key-1", com.estoma.platformbff.domain.operation.OperationStatus.PENDING,
        null, null, null, Instant.now(), Instant.now(), null, null);
    when(idempotency.findByLogicalScope(accountId, "create-person", "key-1"))
        .thenReturn(Optional.of(existing));
    when(operations.findById(operationId)).thenReturn(Optional.of(operation));

    var result = gateway.submit(new CommandSubmission(
        "create-person", "CreatePerson", "{}", "key-1", accountId,
        ActorContext.account(accountId, "GLOBAL", null), UUID.randomUUID(), null));

    assertThat(result.operationId()).isEqualTo(operationId);
    assertThat(result.reused()).isTrue();
    verify(operations, never()).insert(any());
    verify(outbox, never()).insert(any());
  }

  @Test
  void logicalScopeWithDifferentFingerprintIsConflict() {
    UUID accountId = UUID.randomUUID();
    var existing = new IdempotencyRecord(
        "scope", accountId, "create-person", "key-1", "different",
        UUID.randomUUID(), Instant.now(), null);
    when(idempotency.findByLogicalScope(accountId, "create-person", "key-1"))
        .thenReturn(Optional.of(existing));

    assertThatThrownBy(() -> gateway.submit(new CommandSubmission(
        "create-person", "CreatePerson", "{}", "key-1", accountId,
        ActorContext.account(accountId, "GLOBAL", null), UUID.randomUUID(), null)))
        .isInstanceOf(CommandGatewayService.IdempotencyConflictException.class);
  }

  @Test
  void losingInsertRaceDeletesItsPendingOperationAndUsesWinner() {
    UUID accountId = UUID.randomUUID();
    UUID winnerOperationId = UUID.randomUUID();
    var winner = new IdempotencyRecord(
        "winner", accountId, "create-person", "key-1", "fingerprint",
        winnerOperationId, Instant.now(), null);
    var winnerOperation = new ClientOperation(
        winnerOperationId, accountId, "create-person", "CreatePerson", "users", "users",
        UUID.randomUUID(), "key-1", com.estoma.platformbff.domain.operation.OperationStatus.PENDING,
        null, null, null, Instant.now(), Instant.now(), null, null);
    when(idempotency.findByLogicalScope(accountId, "create-person", "key-1"))
        .thenReturn(Optional.empty(), Optional.of(winner));
    when(idempotency.insertIfAbsent(any())).thenReturn(false);
    when(operations.findById(winnerOperationId)).thenReturn(Optional.of(winnerOperation));

    var result = gateway.submit(new CommandSubmission(
        "create-person", "CreatePerson", "{}", "key-1", accountId,
        ActorContext.account(accountId, "GLOBAL", null), UUID.randomUUID(), null));

    assertThat(result.operationId()).isEqualTo(winnerOperationId);
    verify(operations).deletePending(any());
    verify(outbox, never()).insert(any());
  }
}
