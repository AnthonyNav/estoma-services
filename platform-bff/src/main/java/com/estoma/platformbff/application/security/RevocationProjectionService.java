package com.estoma.platformbff.application.security;

import com.estoma.platformbff.application.port.InboxRepository;
import com.estoma.platformbff.domain.messaging.InboxMessage;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RevocationProjectionService {
  private static final String CONSUMER = "bff-revocation-projection";

  private final InboxRepository inbox;
  private final RevocationProjectionPort projection;

  public RevocationProjectionService(
      InboxRepository inbox, RevocationProjectionPort projection) {
    this.inbox = inbox;
    this.projection = projection;
  }

  @Transactional
  public boolean apply(RevocationProjectionEvent event) {
    var metadata = event.metadata();
    Instant now = Instant.now();
    if (!inbox.recordIfNew(new InboxMessage(
        CONSUMER,
        metadata.messageId(),
        "EVENT",
        metadata.messageType(),
        metadata.messageVersion(),
        metadata.producer(),
        metadata.correlationId(),
        metadata.causationId(),
        null,
        metadata.aggregateId(),
        metadata.aggregateVersion(),
        now,
        now))) {
      return true;
    }

    switch (event) {
      case RevocationProjectionEvent.AccountCreated created ->
          projection.linkAccountToPerson(
              created.accountId(), created.personId(), created.sourceVersion(), created.effectiveAt());
      case RevocationProjectionEvent.SessionRevoked revoked ->
          projection.revokeSession(
              revoked.sessionId(), revoked.revokedAtEpochSeconds(), revoked.reasonCode(),
              metadata.messageId(), metadata.occurredAt());
      case RevocationProjectionEvent.AccountSessionsRevoked revoked ->
          projection.revokeAccount(
              revoked.accountId(), revoked.revokedAtEpochSeconds(), revoked.cutoffKind().name(),
              revoked.exceptSessionId(), revoked.reasonCode(),
              metadata.messageId(), metadata.occurredAt());
      case RevocationProjectionEvent.AccountDeactivated deactivated -> {
        projection.linkAccountToPerson(
            deactivated.accountId(), deactivated.personId(),
            deactivated.sourceVersion(), deactivated.effectiveAt());
        projection.revokeAccount(
            deactivated.accountId(), deactivated.revokedAtEpochSeconds(), "ALL", null,
            deactivated.reasonCode(), metadata.messageId(), metadata.occurredAt());
      }
      case RevocationProjectionEvent.PersonDeactivated deactivated ->
          projection.revokePerson(
              deactivated.personId(), deactivated.revokedAtEpochSeconds(),
              deactivated.reasonCode(), metadata.messageId(), metadata.occurredAt());
    }
    return true;
  }
}
