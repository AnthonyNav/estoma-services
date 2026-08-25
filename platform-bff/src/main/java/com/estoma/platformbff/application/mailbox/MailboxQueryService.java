package com.estoma.platformbff.application.mailbox;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessageResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessagesResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxSummaryResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxSystemSummaryResponse;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class MailboxQueryService {
  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;
  private final ProjectionQueryPort projections;

  public MailboxQueryService(LocalAuthorizationService authorization,
      ProjectionReadinessService readiness, ProjectionQueryPort projections) {
    this.authorization = authorization;
    this.readiness = readiness;
    this.projections = projections;
  }

  public MailboxMessagesResponse messages() {
    var accountId = authorization.requireAuthenticatedAccount();
    readiness.requireReady("communications");
    List<MailboxMessageResponse> messages = projections.findVisibleMailboxMessages(accountId).stream()
        .map(message -> new MailboxMessageResponse(
            message.inboxMessageId(), message.communicationId(), message.systemCode(),
            message.communicationType(), message.title(), message.body(), message.createdAt(),
            message.expiresAt()))
        .toList();
    return new MailboxMessagesResponse(messages);
  }

  public MailboxSummaryResponse summary() {
    var accountId = authorization.requireAuthenticatedAccount();
    readiness.requireReady("communications");
    List<MailboxSystemSummaryResponse> systems = projections.summarizeMailbox(accountId).stream()
        .map(summary -> new MailboxSystemSummaryResponse(
            summary.systemCode(), summary.messageCount(), summary.newMessageCount(),
            summary.lastVisitedAt()))
        .toList();
    long total = systems.stream().mapToLong(MailboxSystemSummaryResponse::messageCount).sum();
    long unread = systems.stream().mapToLong(MailboxSystemSummaryResponse::newMessageCount).sum();
    return new MailboxSummaryResponse(total, unread, systems);
  }
}
