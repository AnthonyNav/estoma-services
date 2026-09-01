package com.estoma.platformbff.application.mailbox;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.EffectiveSystemService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessageResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessagesResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxSystemSummaryResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MailboxQueryService {
  private final LocalAuthorizationService authorization;
  private final EffectiveSystemService effectiveSystems;
  private final ProjectionReadinessService readiness;
  private final ProjectionQueryPort projections;

  public MailboxQueryService(LocalAuthorizationService authorization,
      EffectiveSystemService effectiveSystems, ProjectionReadinessService readiness, ProjectionQueryPort projections) {
    this.authorization = authorization;
    this.effectiveSystems = effectiveSystems;
    this.readiness = readiness;
    this.projections = projections;
  }

  public MailboxMessagesResponse messages(String systemCode, int limit, String cursor) {
    if (systemCode == null || systemCode.isBlank() || limit < 1 || limit > 50) {
      throw new IllegalArgumentException("invalid mailbox page request");
    }
    var accountId = authorization.requireAuthenticatedAccount();
    effectiveSystems.requireEffectiveSystem(accountId, systemCode);
    readiness.requireReady("communications");
    var page = projections.findVisibleMailboxMessages(accountId, systemCode, limit, MailboxCursorCodec.decode(cursor));
    List<MailboxMessageResponse> messages = page.items().stream()
        .map(message -> new MailboxMessageResponse(
            message.inboxMessageId(), message.communicationId(), message.systemCode(),
            message.communicationType(), message.title(), message.body(), message.createdAt(),
            message.expiresAt()))
        .toList();
    return new MailboxMessagesResponse(messages, MailboxCursorCodec.encode(page.nextCursor()));
  }

  public MailboxSystemSummaryResponse summary(String systemCode) {
    if (systemCode == null || systemCode.isBlank()) throw new IllegalArgumentException("systemCode is required");
    var accountId = authorization.requireAuthenticatedAccount();
    effectiveSystems.requireEffectiveSystem(accountId, systemCode);
    readiness.requireReady("communications");
    var summary = projections.summarizeMailbox(accountId, systemCode);
    return new MailboxSystemSummaryResponse(summary.systemCode(), summary.messageCount(),
        summary.newMessageCount(), summary.lastVisitedAt());
  }
}
