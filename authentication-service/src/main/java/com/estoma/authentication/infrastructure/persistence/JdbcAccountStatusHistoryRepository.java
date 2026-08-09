package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.AccountStatusHistoryRepository;
import com.estoma.authentication.domain.account.AccountStatusChange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAccountStatusHistoryRepository implements AccountStatusHistoryRepository {

  private final JdbcClient jdbc;

  JdbcAccountStatusHistoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(AccountStatusChange change) {
    jdbc.sql(
            """
            INSERT INTO account_status_history (
              history_id, account_id, previous_status, new_status, reason_code, reason_detail,
              actor_type, actor_account_id, actor_service, correlation_id, occurred_at, account_version
            ) VALUES (
              :historyId, :accountId, CAST(:previousStatus AS account_status), CAST(:newStatus AS account_status), :reasonCode, :reasonDetail,
              :actorType, :actorAccountId, :actorService, :correlationId, :occurredAt, :accountVersion
            )
            """)
        .param("historyId", change.historyId())
        .param("accountId", change.accountId())
        .param("previousStatus", change.previousStatus() == null ? null : change.previousStatus().name())
        .param("newStatus", change.newStatus().name())
        .param("reasonCode", change.reasonCode())
        .param("reasonDetail", change.reasonDetail())
        .param("actorType", change.actor().type())
        .param("actorAccountId", change.actor().accountId())
        .param("actorService", change.actor().service())
        .param("correlationId", change.correlationId())
        .param("occurredAt", Timestamps.toTimestamp(change.occurredAt()))
        .param("accountVersion", change.accountVersion())
        .update();
  }
}
