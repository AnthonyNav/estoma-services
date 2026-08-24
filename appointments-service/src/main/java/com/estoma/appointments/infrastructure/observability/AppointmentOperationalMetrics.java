package com.estoma.appointments.infrastructure.observability;

import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Low-cardinality operational metrics backed by durable state plus messaging counters. */
@Component
public final class AppointmentOperationalMetrics {
  private final MeterRegistry registry;
  private final JdbcClient jdbc;
  private final Counter publisherConfirmed;
  private final Counter publisherFailed;
  private final Counter publisherQuarantined;
  private final Map<String, Counter> retryCounters = new ConcurrentHashMap<>();
  private final Map<String, Counter> dlqCounters = new ConcurrentHashMap<>();

  public AppointmentOperationalMetrics(MeterRegistry registry, JdbcClient jdbc) {
    this.registry = registry;
    this.jdbc = jdbc;
    publisherConfirmed = Counter.builder("appointments.messaging.publisher.confirmed").register(registry);
    publisherFailed = Counter.builder("appointments.messaging.publisher.failed").register(registry);
    publisherQuarantined = Counter.builder("appointments.messaging.publisher.quarantined").register(registry);

    gauge("appointments.outbox.pending", "SELECT count(*)::double precision FROM outbox_messages WHERE status='PENDING'");
    gauge("appointments.outbox.failed", "SELECT count(*)::double precision FROM outbox_messages WHERE status='FAILED'");
    gauge("appointments.outbox.oldest.age.seconds", """
        SELECT COALESCE(EXTRACT(EPOCH FROM (now()-MIN(occurred_at))),0)::double precision
        FROM outbox_messages WHERE status IN ('PENDING','FAILED','PROCESSING')
        """);
    gauge("appointments.pending.projection.events", """
        SELECT count(*)::double precision FROM pending_projection_events
        WHERE status IN ('PENDING','PROCESSING')
        """);
    gauge("appointments.pending.execution.events", """
        SELECT count(*)::double precision FROM pending_wash_execution_events
        WHERE status IN ('PENDING','PROCESSING')
        """);
    gauge("appointments.pending.capacity.evaluations", """
        SELECT count(*)::double precision FROM pending_capacity_evaluation
        WHERE status IN ('PENDING','PROCESSING')
        """);
    gauge("appointments.recovery.jobs", "SELECT count(*)::double precision FROM projection_recovery_jobs");
    gauge("appointments.capacity.loss.cancellations", """
        SELECT count(*)::double precision FROM appointments
        WHERE status='CANCELLED' AND cancellation_type='CLINIC'
          AND cancellation_subreason='CAPACITY_LOSS'
        """);
    gauge("appointments.missed.applied", "SELECT count(*)::double precision FROM appointments WHERE status='MISSED'");

    for (String projection : ProjectionRecoveryCoordinator.CRITICAL_PROJECTIONS) {
      Gauge.builder("appointments.projection.ready", this, ignored -> projectionReady(projection))
          .tag("projection", projection)
          .description("1 when the critical local projection is READY, otherwise 0")
          .register(registry);
    }
  }

  public void publisherConfirmed() { publisherConfirmed.increment(); }
  public void publisherFailed() { publisherFailed.increment(); }
  public void publisherQuarantined() { publisherQuarantined.increment(); }

  public void retryRouted(String queue) {
    retryCounters.computeIfAbsent(queue, value -> Counter.builder("appointments.messaging.retry.routed")
        .tag("queue", value).register(registry)).increment();
  }

  public void dlqRouted(String queue) {
    dlqCounters.computeIfAbsent(queue, value -> Counter.builder("appointments.messaging.dlq.routed")
        .tag("queue", value).register(registry)).increment();
  }

  private void gauge(String name, String sql) {
    Gauge.builder(name, this, ignored -> scalar(sql)).register(registry);
  }

  private double projectionReady(String projection) {
    return jdbc.sql("""
        SELECT CASE WHEN EXISTS(
          SELECT 1 FROM projection_recovery_state
          WHERE projection_name=:projection AND status='READY'
        ) THEN 1.0 ELSE 0.0 END
        """).param("projection", projection).query(Double.class).single();
  }

  private double scalar(String sql) {
    Double value = jdbc.sql(sql).query(Double.class).single();
    return value == null ? 0.0 : value;
  }
}
