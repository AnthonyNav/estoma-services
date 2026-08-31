package com.estoma.platformbff.application.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.security.EffectiveSystemService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MailboxQueryServiceTest {
  @Mock LocalAuthorizationService authorization;
  @Mock EffectiveSystemService effectiveSystems;
  @Mock ProjectionReadinessService readiness;
  @Mock ProjectionQueryPort projections;
  @InjectMocks MailboxQueryService service;

  @Test
  void readsOnlyTheAuthenticatedAccountsEffectiveSystemRowsAndBuildsTheScopedSummary() {
    UUID accountId = UUID.randomUUID();
    when(authorization.requireAuthenticatedAccount()).thenReturn(accountId);
    doNothing().when(effectiveSystems).requireEffectiveSystem(accountId, "LAVADO_ULTRASONICO");
    when(projections.findVisibleMailboxMessages(accountId, "LAVADO_ULTRASONICO", 20, null)).thenReturn(
        new ProjectionQueryPort.MailboxPage(List.of(
        new ProjectionQueryPort.MailboxMessage(UUID.randomUUID(), UUID.randomUUID(), "LAVADO_ULTRASONICO",
            "APPOINTMENT", "Title", "Body", Instant.parse("2026-08-01T10:00:00Z"), null)), null));
    when(projections.summarizeMailbox(accountId, "LAVADO_ULTRASONICO")).thenReturn(
        new ProjectionQueryPort.MailboxSummary("LAVADO_ULTRASONICO", 3, 2,
            Instant.parse("2026-08-01T09:00:00Z")));

    var messages = service.messages("LAVADO_ULTRASONICO", 20, null);
    var summary = service.summary("LAVADO_ULTRASONICO");

    assertThat(messages.items()).hasSize(1);
    assertThat(summary.messageCount()).isEqualTo(3);
    assertThat(summary.newMessageCount()).isEqualTo(2);
    assertThat(summary.systemCode()).isEqualTo("LAVADO_ULTRASONICO");
    verify(projections).findVisibleMailboxMessages(accountId, "LAVADO_ULTRASONICO", 20, null);
    verify(projections).summarizeMailbox(accountId, "LAVADO_ULTRASONICO");
    verify(effectiveSystems, times(2)).requireEffectiveSystem(accountId, "LAVADO_ULTRASONICO");
    verify(readiness, times(2)).requireReady("communications");
  }
}
