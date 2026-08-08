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

/**
 * Consumes {@code AccountCreated}/{@code AccountActivated}/{@code
 * AccountDeactivated}/{@code LoginIdentifierChanged} to keep {@code
 * recipient_projection} current (events.md §5). This is the only consumed
 * event family that carries a concrete {@code accountId}, so it is the
 * only one that creates a {@code recipient_projection} row (see {@code
 * V7__recipient_projection.sql}).
 */
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
        ConsumedEventTypes.ACCOUNT_CREATED, ConsumedEventTypes.ACCOUNT_ACTIVATED, ConsumedEventTypes.ACCOUNT_DEACTIVATED,
        ConsumedEventTypes.LOGIN_IDENTIFIER_CHANGED);
  }

  @Override
  public String consumerName() {
    return ConsumerNames.RECIPIENT_PROJECTION;
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    AccountProjectionEventPayload payload = codec.convertPayload(envelope, AccountProjectionEventPayload.class);
    repository.upsertAccountIfNewer(payload.accountId(), payload.personId(), payload.status(), payload.sourceVersion(), Instant.now());
    return ProjectionHandlerResult.NONE;
  }
}
