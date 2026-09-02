package com.estoma.communications.application.projection;

import com.estoma.communications.application.port.AcademicPeriodProjectionRepository;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.consumed.AcademicPeriodActivatedPayload;
import com.estoma.communications.contracts.consumed.AcademicPeriodClosedPayload;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.communications.infrastructure.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Applies Academic period lifecycle events with per-period source-version fencing. */
@Component
class AcademicPeriodProjectionHandler implements ProjectionHandler {

  private final AcademicPeriodProjectionRepository repository;
  private final AcademicPeriodRecoveryCoordinator recovery;
  private final EnvelopeJsonCodec codec;

  AcademicPeriodProjectionHandler(
      AcademicPeriodProjectionRepository repository,
      AcademicPeriodRecoveryCoordinator recovery,
      EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.recovery = recovery;
    this.codec = codec;
  }

  @Override
  public List<String> consumedMessageTypes() {
    return List.of(ConsumedEventTypes.ACADEMIC_PERIOD_ACTIVATED, ConsumedEventTypes.ACADEMIC_PERIOD_CLOSED);
  }

  @Override
  public String consumerName() {
    return ConsumerNames.ACADEMIC_PERIOD_PROJECTION;
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    if (envelope.aggregateVersion() == null || envelope.aggregateVersion() < 1) {
      throw new IllegalArgumentException("Academic period event requires aggregateVersion");
    }
    Instant now = Instant.now();
    if (ConsumedEventTypes.ACADEMIC_PERIOD_ACTIVATED.equals(envelope.messageType())) {
      AcademicPeriodActivatedPayload payload = codec.convertPayload(envelope, AcademicPeriodActivatedPayload.class);
      if (payload.academicPeriodId() == null || payload.code() == null || payload.code().isBlank()
          || payload.startDate() == null || payload.endDate() == null || payload.endDate().isBefore(payload.startDate())
          || !"ACTIVE".equals(payload.status())) {
        throw new IllegalArgumentException("invalid AcademicPeriodActivated payload");
      }
      boolean requiresRecovery = repository.applyActive(
          new AcademicPeriodProjectionRepository.ActiveAcademicPeriod(
              payload.academicPeriodId(), payload.code(), payload.startDate(), payload.endDate(), envelope.aggregateVersion()),
          envelope.occurredAt(), now);
      if (requiresRecovery) {
        recovery.requestBootstrap();
      }
      return ProjectionHandlerResult.NONE;
    }

    AcademicPeriodClosedPayload payload = codec.convertPayload(envelope, AcademicPeriodClosedPayload.class);
    if (payload.academicPeriodId() == null || !"CLOSED".equals(payload.status())) {
      throw new IllegalArgumentException("invalid AcademicPeriodClosed payload");
    }
    repository.applyClosed(payload.academicPeriodId(), envelope.aggregateVersion(), envelope.occurredAt(), now);
    return ProjectionHandlerResult.NONE;
  }
}
