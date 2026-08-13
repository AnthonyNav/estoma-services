package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.AdministrativeClosureRepository;
import com.estoma.washadministration.domain.closure.AdministrativeClosure;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAdministrativeClosureRepository implements AdministrativeClosureRepository {

  private static final String COLUMNS =
      "administrative_closure_id, service_calendar_id, scope, cabin_id, tank_id, starts_at, ends_at, "
          + "reason, status, version, created_by_account_id, cancelled_by_account_id, cancellation_reason, "
          + "created_at, updated_at, cancelled_at";
  private final JdbcClient jdbc;

  JdbcAdministrativeClosureRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AdministrativeClosure> findById(UUID id) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM administrative_closures WHERE administrative_closure_id = :id")
        .param("id", id)
        .query(ClosureRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public void insert(AdministrativeClosure closure) {
    jdbc.sql(
            """
            INSERT INTO administrative_closures (
              administrative_closure_id, service_calendar_id, scope, cabin_id, tank_id, starts_at, ends_at,
              reason, status, version, created_by_account_id, cancelled_by_account_id, cancellation_reason,
              created_at, updated_at, cancelled_at
            ) VALUES (
              :id, :calendarId, CAST(:scope AS administrative_closure_scope), :cabinId, :tankId, :startsAt, :endsAt,
              :reason, CAST(:status AS administrative_closure_status), :version, :createdBy, :cancelledBy, :cancellationReason,
              :createdAt, :updatedAt, :cancelledAt
            )
            """)
        .param("id", closure.id())
        .param("calendarId", closure.serviceCalendarId())
        .param("scope", closure.scope().name())
        .param("cabinId", closure.cabinId())
        .param("tankId", closure.tankId())
        .param("startsAt", Timestamps.toTimestamp(closure.startsAt()))
        .param("endsAt", Timestamps.toTimestamp(closure.endsAt()))
        .param("reason", closure.reason().value())
        .param("status", closure.status().name())
        .param("version", closure.version())
        .param("createdBy", closure.createdByAccountId())
        .param("cancelledBy", closure.cancelledByAccountId())
        .param("cancellationReason", closure.cancellationReason() == null ? null : closure.cancellationReason().value())
        .param("createdAt", Timestamps.toTimestamp(closure.createdAt()))
        .param("updatedAt", Timestamps.toTimestamp(closure.updatedAt()))
        .param("cancelledAt", Timestamps.toTimestamp(closure.cancelledAt()))
        .update();
  }

  @Override
  public int update(AdministrativeClosure closure, int expectedVersion) {
    OptimisticVersionGuard.requireNext(expectedVersion, closure.version());
    return jdbc.sql(
            """
            UPDATE administrative_closures SET
              status = CAST(:status AS administrative_closure_status), version = version + 1,
              cancelled_by_account_id = :cancelledBy, cancellation_reason = :cancellationReason,
              cancelled_at = :cancelledAt, updated_at = :updatedAt
             WHERE administrative_closure_id = :id AND version = :expectedVersion
            """)
        .param("id", closure.id())
        .param("status", closure.status().name())
        .param("cancelledBy", closure.cancelledByAccountId())
        .param("cancellationReason", closure.cancellationReason() == null ? null : closure.cancellationReason().value())
        .param("cancelledAt", Timestamps.toTimestamp(closure.cancelledAt()))
        .param("updatedAt", Timestamps.toTimestamp(closure.updatedAt()))
        .param("expectedVersion", expectedVersion)
        .update();
  }
}
