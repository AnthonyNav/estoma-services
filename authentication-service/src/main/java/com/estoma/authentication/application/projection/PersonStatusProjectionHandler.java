package com.estoma.authentication.application.projection;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.application.port.SessionRepository;
import com.estoma.authentication.application.EventToAppend;
import com.estoma.authentication.contracts.AuthenticationEvent;
import com.estoma.authentication.contracts.event.AllAccountSessionsRevokedPayload;
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
  private final AccountRepository accountRepository;
  private final SessionRepository sessionRepository;
  private final EnvelopeJsonCodec codec;

  PersonStatusProjectionHandler(
      PersonStatusProjectionRepository repository,
      AccountRepository accountRepository,
      SessionRepository sessionRepository,
      EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.accountRepository = accountRepository;
    this.sessionRepository = sessionRepository;
    this.codec = codec;
  }

  @Override
  public List<String> consumedMessageTypes() {
    return List.of(ConsumedEventTypes.PERSON_CREATED, ConsumedEventTypes.PERSON_ACTIVATED, ConsumedEventTypes.PERSON_DEACTIVATED);
  }

  @Override
  public ProjectionHandlerResult handle(MessageEnvelope envelope) {
    PersonStatusEventPayload payload = codec.convertPayload(envelope, PersonStatusEventPayload.class);
    Instant now = Instant.now();
    boolean applied = repository.upsertIfNewer(payload.personId(), payload.status(), envelope.aggregateVersion(), now);
    if (applied && ConsumedEventTypes.PERSON_DEACTIVATED.equals(envelope.messageType())) {
      var events = accountRepository.findIdsByPersonId(payload.personId()).stream()
          .filter(accountId -> sessionRepository.revokeAllActiveForAccount(accountId, "PERSON_DEACTIVATED", now) > 0)
           .map(accountId -> new EventToAppend(AuthenticationEvent.ALL_ACCOUNT_SESSIONS_REVOKED.wireName(),
               new AllAccountSessionsRevokedPayload(accountId, "PERSON_DEACTIVATED", now), accountId, null))
          .toList();
      return new ProjectionHandlerResult(events);
    }
    return ProjectionHandlerResult.NONE;
  }
}
