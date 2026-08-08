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

/**
 * Consumes {@code PersonCreated}/{@code PersonUpdated}/{@code
 * PersonActivated}/{@code PersonDeactivated} to keep {@code
 * recipient_projection}'s person-derived columns current (events.md §5).
 * Updates every existing row for this {@code personId} -- see {@code
 * V7__recipient_projection.sql} for why a Person event cannot create a row
 * on its own.
 */
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
        ConsumedEventTypes.PERSON_CREATED, ConsumedEventTypes.PERSON_UPDATED, ConsumedEventTypes.PERSON_ACTIVATED, ConsumedEventTypes.PERSON_DEACTIVATED);
  }

  @Override
  public String consumerName() {
    return ConsumerNames.RECIPIENT_PROJECTION;
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    PersonProjectionEventPayload payload = codec.convertPayload(envelope, PersonProjectionEventPayload.class);
    repository.updatePersonIfNewer(payload.personId(), payload.displayName(), payload.institutionalEmail(), payload.status(), payload.sourceVersion(), Instant.now());
    return ProjectionHandlerResult.NONE;
  }
}
