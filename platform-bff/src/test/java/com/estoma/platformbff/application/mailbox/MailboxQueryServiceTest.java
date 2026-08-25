package com.estoma.platformbff.application.mailbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
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
  @Mock ProjectionReadinessService readiness;
  @Mock ProjectionQueryPort projections;
  @InjectMocks MailboxQueryService service;

  @Test
  void readsOnlyTheAuthenticatedAccountsProjectionRowsAndAggregatesTheSummary() {
    UUID accountId = UUID.randomUUID();
    when(authorization.requireAuthenticatedAccount()).thenReturn(accountId);
    when(projections.findVisibleMailboxMessages(accountId)).thenReturn(List.of(
        new ProjectionQueryPort.MailboxMessage(UUID.randomUUID(), UUID.randomUUID(), "LAVADO_ULTRASONICO",
            "APPOINTMENT", "Title", "Body", Instant.parse("2026-08-01T10:00:00Z"), null)));
    when(projections.summarizeMailbox(accountId)).thenReturn(List.of(
        new ProjectionQueryPort.MailboxSummary("LAVADO_ULTRASONICO", 3, 2,
            Instant.parse("2026-08-01T09:00:00Z")),
        new ProjectionQueryPort.MailboxSummary("ACADEMICO", 1, 0,
            Instant.parse("2026-08-01T09:30:00Z"))));

    var messages = service.messages();
    var summary = service.summary();

    assertThat(messages.messages()).hasSize(1);
    assertThat(summary.messageCount()).isEqualTo(4);
    assertThat(summary.newMessageCount()).isEqualTo(2);
    assertThat(summary.systems()).extracting(item -> item.systemCode())
        .containsExactly("LAVADO_ULTRASONICO", "ACADEMICO");
    verify(projections).findVisibleMailboxMessages(accountId);
    verify(projections).summarizeMailbox(accountId);
    verify(readiness, times(2)).requireReady("communications");
  }
}
