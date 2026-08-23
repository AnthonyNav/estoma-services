package com.estoma.communications.application.projection;

import com.estoma.communications.application.port.RecipientProjectionRepository;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.consumed.AccountProjectionEventPayload;
import com.estoma.communications.contracts.consumed.ConsumedEventTypes;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import com.estoma.communications.infrastructure.observability.ConsumerNames;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

/** Projects canonical Authentication Account events into the local recipient read model. */
@Component
class AccountRecipientProjectionHandler implements ProjectionHandler {

  private final RecipientProjectionRepository repository;
  private final EnvelopeJsonCodec codec;

  AccountRecipientProjectionHandler(RecipientProjectionRepository repository, EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.codec = codec;
  }

  @Override
  public List<String> consumedMessageTypes() {
    return List.of(
        ConsumedEventTypes.ACCOUNT_CREATED,
        ConsumedEventTypes.ACCOUNT_ACTIVATED,
        ConsumedEventTypes.ACCOUNT_DEACTIVATED);
  }

  @Override
  public String consumerName() {
    return ConsumerNames.RECIPIENT_PROJECTION;
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    AccountProjectionEventPayload payload = codec.convertPayload(envelope, AccountProjectionEventPayload.class);
    if (payload == null || payload.accountId() == null || payload.personId() == null
        || payload.status() == null || envelope.aggregateVersion() == null || envelope.aggregateVersion() < 1) {
      throw new IllegalArgumentException("invalid canonical Account event for recipient projection");
    }
    repository.upsertAccountIfNewer(
        payload.accountId(), payload.personId(), payload.status(), envelope.aggregateVersion(), Instant.now());
    return ProjectionHandlerResult.NONE;
  }
}
