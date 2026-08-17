package com.estoma.authorization.infrastructure.messaging;

import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Durable-state observability for Authorization technical tables.
 *
 * <p>These meters deliberately query only Authorization-owned technical state. They keep metrics
 * derived from committed state instead of incrementing counters before the surrounding business
 * transaction has committed.
 */
@Component
final class AuthorizationOperationalMetrics implements MeterBinder {

  private final JdbcClient jdbc;

  AuthorizationOperationalMetrics(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public void bindTo(MeterRegistry registry) {
    Gauge.builder("authorization_outbox_pending", this, ignored -> count(
            "SELECT count(*) FROM outbox_messages WHERE status = 'PENDING'"))
        .description("Current pending Authorization Outbox messages")
        .register(registry);

    Gauge.builder("authorization_outbox_oldest_age_seconds", this, ignored -> scalar(
            """
            SELECT COALESCE(
              GREATEST(0, EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(available_at)))),
              0
            )::double precision
            FROM outbox_messages
            WHERE status = 'PENDING'
            """))
        .description("Age in seconds of the oldest available pending Outbox message")
        .register(registry);

    Gauge.builder("authorization_outbox_processing_stuck", this, ignored -> count(
            """
            SELECT count(*) FROM outbox_messages
            WHERE status = 'PROCESSING' AND lease_until < CURRENT_TIMESTAMP
            """))
        .description("Outbox messages whose PROCESSING lease has expired")
        .register(registry);

    Gauge.builder("authorization_projection_deferred", this, ignored -> count(
            """
            SELECT count(*) FROM processed_messages
            WHERE message_kind = 'EVENT' AND status = 'DEFERRED'
            """))
        .description("Current deferred projection Events")
        .register(registry);

    Gauge.builder("authorization_projection_lag_seconds", this, ignored -> scalar(
            """
            SELECT COALESCE(
              GREATEST(0, EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - MIN(received_at)))),
              0
            )::double precision
            FROM processed_messages
            WHERE message_kind = 'EVENT' AND status = 'DEFERRED'
            """))
        .description("Age in seconds of the oldest deferred projection Event")
        .register(registry);

    FunctionCounter.builder("authorization.projection.recovery.jobs", this, ignored -> count(
            "SELECT count(*) FROM projection_recovery_jobs"))
        .description("Durable projection recovery jobs created")
        .register(registry);

    FunctionCounter.builder("authorization.projection.recovery.failed", this, ignored -> count(
            "SELECT count(*) FROM projection_recovery_jobs WHERE status = 'FAILED'"))
        .description("Projection recovery jobs that reached FAILED")
        .register(registry);

    Gauge.builder("authorization_projection_recovery_active", this, ignored -> count(
            """
            SELECT count(*) FROM projection_recovery_jobs
            WHERE status IN ('PENDING', 'WAITING_RESULT')
            """))
        .description("Current active projection recovery jobs")
        .register(registry);

    Gauge.builder("authorization_projection_recovery_duration_seconds", this, ignored -> scalar(
            """
            SELECT COALESCE(
              AVG(EXTRACT(EPOCH FROM (updated_at - created_at))),
              0
            )::double precision
            FROM projection_recovery_jobs
            WHERE status IN ('COMPLETED', 'FAILED')
            """))
        .description("Average terminal projection recovery duration in seconds")
        .register(registry);

    FunctionCounter.builder("authorization.projection.bootstrap.page", this, ignored -> count(
            """
            SELECT count(*) FROM processed_messages
            WHERE consumer_name = 'authorization-recovery-results'
              AND status = 'APPLIED'
              AND message_type LIKE 'List%SnapshotsPageResult'
            """))
        .description("Durably applied FULL projection bootstrap pages")
        .register(registry);

    FunctionCounter.builder("authorization.projection.deferred.reapplied", this, ignored -> count(
            """
            SELECT count(*)
            FROM authorization_audit_entries audit
            JOIN processed_messages inbox
              ON inbox.consumer_name = 'authorization-events'
             AND inbox.message_id = audit.source_message_id
            WHERE audit.action = 'PROJECTION_APPLIED'
              AND inbox.applied_at > inbox.received_at
            """))
        .description("Deferred projection Events successfully reapplied")
        .register(registry);

    FunctionCounter.builder("authorization.projection.deferred.stale.resolved", this, ignored -> count(
            """
            SELECT count(*)
            FROM authorization_audit_entries audit
            JOIN processed_messages inbox
              ON inbox.consumer_name = 'authorization-events'
             AND inbox.message_id = audit.source_message_id
            WHERE audit.action = 'PROJECTION_STALE_RESOLVED'
              AND inbox.applied_at > inbox.received_at
            """))
        .description("Deferred projection Events resolved as stale")
        .register(registry);

    Gauge.builder("authorization_eligibility_pending", this, ignored -> count(
            """
            SELECT count(*) FROM academic_eligibility_projection
            WHERE pending_reason IS NOT NULL
            """))
        .description("Current eligibility rows awaiting a provisioning prerequisite")
        .register(registry);

    FunctionCounter.builder("authorization.eligibility.reconciliation", this, ignored -> count(
            """
            SELECT count(*) FROM authorization_audit_entries
            WHERE action = 'ELIGIBILITY_RECONCILIATION_REQUESTED'
            """))
        .description("Eligibility reconciliation requests durably recorded")
        .register(registry);
  }

  private double count(String sql) {
    try {
      return jdbc.sql(sql).query(Long.class).single().doubleValue();
    } catch (RuntimeException unavailable) {
      return Double.NaN;
    }
  }

  private double scalar(String sql) {
    try {
      return jdbc.sql(sql).query(Double.class).single();
    } catch (RuntimeException unavailable) {
      return Double.NaN;
    }
  }
}
