package com.estoma.authentication.application.projection;

import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.consumed.ConsumedEventTypes;
import com.estoma.authentication.contracts.consumed.PersonStatusEventPayload;
import com.estoma.authentication.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Consumes {@code PersonCreated}/{@code PersonActivated}/{@code
 * PersonDeactivated} to keep {@code person_status_projection} current
 * (events.md §9, data-model.md §9). Does not activate or deactivate any
 * account as a side effect -- activating a Person never auto-activates
 * accounts, and this handler only ever updates the projection table.
 */
@Component
class PersonStatusProjectionHandler implements ProjectionHandler {

  private final PersonStatusProjectionRepository repository;
  private final EnvelopeJsonCodec codec;

  PersonStatusProjectionHandler(PersonStatusProjectionRepository repository, EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.codec = codec;
  }

  @Override
  public List<String> consumedMessageTypes() {
    return List.of(ConsumedEventTypes.PERSON_CREATED, ConsumedEventTypes.PERSON_ACTIVATED, ConsumedEventTypes.PERSON_DEACTIVATED);
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    PersonStatusEventPayload payload = codec.convertPayload(envelope, PersonStatusEventPayload.class);
    repository.upsertIfNewer(payload.personId(), payload.status(), payload.sourceVersion(), Instant.now());
    return ProjectionHandlerResult.NONE;
  }
}
