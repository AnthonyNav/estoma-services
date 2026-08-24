package com.estoma.communications.application.projection;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.contracts.consumed.PersonProjectionEventPayload;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.communications.infrastructure.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Projects canonical Users Person events into the local recipient read model. */
@Component
class PersonRecipientProjectionHandler implements ProjectionHandler {

  private final RecipientProjectionRepository repository;
  private final EnvelopeJsonCodec codec;

  PersonRecipientProjectionHandler(RecipientProjectionRepository repository, EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.codec = codec;
  }

  @Override
  public List<String> consumedMessageTypes() {
    return List.of(
        ConsumedEventTypes.PERSON_CREATED,
        ConsumedEventTypes.PERSON_UPDATED,
        ConsumedEventTypes.PERSON_ACTIVATED,
        ConsumedEventTypes.PERSON_DEACTIVATED);
  }

  @Override
  public String consumerName() {
    return ConsumerNames.RECIPIENT_PROJECTION;
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    PersonProjectionEventPayload payload = codec.convertPayload(envelope, PersonProjectionEventPayload.class);
    if (payload == null || payload.personId() == null || envelope.aggregateVersion() == null || envelope.aggregateVersion() < 1) {
      throw new IllegalArgumentException("invalid canonical Person event for recipient projection");
    }
    Instant now = Instant.now();
    switch (envelope.messageType()) {
      case ConsumedEventTypes.PERSON_CREATED -> repository.updatePersonIfNewer(
          payload.personId(), payload.displayName(), payload.institutionalEmail(), payload.status(), envelope.aggregateVersion(), now);
      case ConsumedEventTypes.PERSON_UPDATED -> repository.updatePersonDetailsIfNewer(
          payload.personId(), payload.displayName(), payload.institutionalEmail(), envelope.aggregateVersion(), now);
      case ConsumedEventTypes.PERSON_ACTIVATED, ConsumedEventTypes.PERSON_DEACTIVATED -> repository.updatePersonStatusIfNewer(
          payload.personId(), payload.status(), envelope.aggregateVersion(), now);
      default -> throw new IllegalStateException("unsupported Person event " + envelope.messageType());
    }
    return ProjectionHandlerResult.NONE;
  }
}
