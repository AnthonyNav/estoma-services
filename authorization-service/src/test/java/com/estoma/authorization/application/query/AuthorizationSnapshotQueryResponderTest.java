package com.estoma.authorization.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.estoma.authorization.application.port.OutboxRepository;
import com.estoma.authorization.application.port.TransactionRunner;
import com.estoma.authorization.domain.model.ActorRef;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AuthorizationSnapshotQueryResponderTest {

  private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

  @Test
  void terminalTechnicalFailureIsDurablyRecordedAsFailedResult() {
    OutboxRepository outbox = mock(OutboxRepository.class);
    AuthorizationSnapshotQueryResponder responder = new AuthorizationSnapshotQueryResponder(
        directTransactions(),
        mock(AuthorizationSnapshotQueryService.class),
        outbox,
        Clock.fixed(NOW, ZoneOffset.UTC));
    UUID queryId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    AuthorizationQueryMetadata metadata = new AuthorizationQueryMetadata(
        queryId,
        "GetAccountAuthorizationSnapshot",
        "bff",
        NOW,
        correlationId,
        null,
        operationId,
        ActorRef.system("bff"));

    responder.respondFailed(metadata, "TECHNICAL_FAILURE");

    ArgumentCaptor<OutboxRepository.OutboxMessage> captured =
        ArgumentCaptor.forClass(OutboxRepository.OutboxMessage.class);
    verify(outbox).append(captured.capture());
    var message = captured.getValue();
    assertThat(message.messageKind()).isEqualTo("RESULT");
    assertThat(message.messageType()).isEqualTo("GetAccountAuthorizationSnapshotResult");
    assertThat(message.producer()).isEqualTo("authorization");
    assertThat(message.routingKey()).isEqualTo("bff");
    assertThat(message.causationId()).isEqualTo(queryId);
    assertThat(message.correlationId()).isEqualTo(correlationId);
    assertThat(message.operationId()).isEqualTo(operationId);
    assertThat(message.idempotencyKey()).isNull();
    assertThat(message.payloadJson())
        .contains("\"status\":\"FAILED\"")
        .contains("\"code\":\"TECHNICAL_FAILURE\"")
        .doesNotContain("\"data\"");
  }

  private static TransactionRunner directTransactions() {
    return new TransactionRunner() {
      @Override
      public <T> T inTransaction(Supplier<T> operation) {
        return operation.get();
      }
    };
  }
}
