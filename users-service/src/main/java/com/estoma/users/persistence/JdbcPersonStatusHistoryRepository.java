package com.estoma.users.persistence;

import com.estoma.users.application.port.PersonStatusHistoryRepository;
import com.estoma.users.domain.PersonStatusChange;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcPersonStatusHistoryRepository implements PersonStatusHistoryRepository {

  private final JdbcClient jdbc;

  JdbcPersonStatusHistoryRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void insert(PersonStatusChange change) {
    jdbc.sql(
            """
            INSERT INTO person_status_history (
              status_change_id, person_id, previous_status, new_status,
              reason_code, reason_detail,
              changed_by_account_id, correlation_id, changed_at
            ) VALUES (
              :statusChangeId, :personId,
              CAST(:previousStatus AS person_status), CAST(:newStatus AS person_status),
              :reasonCode, :reasonDetail,
              :changedByAccountId, :correlationId, :changedAt
            )
            """)
        .param("statusChangeId", change.statusChangeId())
        .param("personId", change.personId())
        .param("previousStatus", change.previousStatus() == null ? null : change.previousStatus().name())
        .param("newStatus", change.newStatus().name())
        .param("reasonCode", change.reasonCode())
        .param("reasonDetail", change.reasonDetail())
        .param("changedByAccountId", change.changedByAccountId())
        .param("correlationId", change.correlationId())
        .param("changedAt", Timestamps.toTimestamp(change.changedAt()))
        .update();
  }
}
