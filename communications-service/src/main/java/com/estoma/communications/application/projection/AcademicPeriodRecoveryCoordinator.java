package com.estoma.communications.application.projection;

import com.estoma.communications.application.port.AcademicPeriodProjectionRepository;
import com.estoma.communications.application.port.InboxPort;
import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.application.port.OutboxPort;
import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.consumed.ActiveAcademicPeriodSnapshotResultPayload;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.communications.infrastructure.observability.ConsumerNames;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Durable bootstrap/retry loop for the local Academic period projection. */
@Component
public class AcademicPeriodRecoveryCoordinator {

  static final String QUERY_TYPE = "GetActiveAcademicPeriodSnapshot";
  static final String RESULT_TYPE = "GetActiveAcademicPeriodSnapshotResult";
  private static final Duration RETRY_AFTER = Duration.ofMinutes(1);

  private final AcademicPeriodProjectionRepository repository;
  private final InboxPort inbox;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;

  AcademicPeriodRecoveryCoordinator(
      AcademicPeriodProjectionRepository repository,
      InboxPort inbox,
      OutboxPort outbox,
      EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.inbox = inbox;
    this.outbox = outbox;
    this.codec = codec;
  }

  @Transactional
  public void requestBootstrap() {
    appendRecoveryQuery(UUID.randomUUID(), Instant.now(), false);
  }

  @Transactional
  public void retryIfOverdue() {
    appendRecoveryQuery(UUID.randomUUID(), Instant.now(), true);
  }

  @Transactional
  public boolean handleResult(MessageEnvelope envelope) {
    if (!inbox.tryInsert(ConsumerNames.ACADEMIC_PERIOD_RECOVERY, envelope.messageId(), envelope.correlationId())) {
      return false;
    }
    ActiveAcademicPeriodSnapshotResultPayload payload =
        codec.convertPayload(envelope, ActiveAcademicPeriodSnapshotResultPayload.class);
    Optional<AcademicPeriodProjectionRepository.ActiveAcademicPeriod> active = "FOUND".equals(payload.status())
        ? Optional.of(new AcademicPeriodProjectionRepository.ActiveAcademicPeriod(
            payload.data().academicPeriodId(), payload.data().code(), payload.data().startDate(), payload.data().endDate(),
            payload.data().sourceVersion()))
        : Optional.empty();
    if (active.isPresent()) {
      repository.recordRecoveryActive(active.orElseThrow(), payload.data().snapshotGeneratedAt(), Instant.now());
    }
    return repository.completeRecovery(
        envelope.causationId(), active,
        active.map(ignored -> payload.data().snapshotGeneratedAt()).orElse(envelope.occurredAt()), Instant.now());
  }

  private void appendRecoveryQuery(UUID queryMessageId, Instant now, boolean onlyIfOverdue) {
    if (onlyIfOverdue && !repository.retryRecoveryIfOverdue(queryMessageId, now, now.minus(RETRY_AFTER))) {
      return;
    }
    if (!onlyIfOverdue) {
      repository.startRecovery(queryMessageId, now);
    }
    outbox.append(new OutboxAppendRequest(
        queryMessageId, QUERY_TYPE, 1, MessageKind.QUERY.name(), null, null, queryMessageId, null,
        codec.writeValueAsString(Map.of()), now, UUID.randomUUID(), null, Actor.system("communications"), "academic"));
  }
}
