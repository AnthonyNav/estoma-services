package com.estoma.washexecution.infrastructure.messaging.inbound;

import com.estoma.washexecution.application.port.ProjectionRecoveryRepository;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public final class ProjectionTrustCoordinator {
  private static final String EVENT_CONSUMER = "wash-execution.events";

  private final ProjectionRecoveryRepository recovery;
  private final JdbcTemplate jdbc;

  public ProjectionTrustCoordinator(ProjectionRecoveryRepository recovery, JdbcTemplate jdbc) {
    this.recovery = recovery;
    this.jdbc = jdbc;
  }

  public void deferred(String producer, String reason) {
    String projection = projection(producer);
    recovery.mark(projection, producer, "DEGRADED", "DEFERRED:" + reason, Instant.now());
  }

  public void recoveryCompleted(String producer) {
    String projection = projection(producer);
    if (deferredCount(producer) == 0) {
      recovery.mark(projection, producer, "READY", null, Instant.now());
    } else {
      recovery.mark(
          projection,
          producer,
          "DEGRADED",
          "DEFERRED:RECOVERY_RECONCILIATION_PENDING",
          Instant.now());
    }
  }

  public void maybeRestoreAfterReplay(String producer) {
    String projection = projection(producer);
    var state = recovery.find(projection);
    if (state.isEmpty()
        || !"DEGRADED".equals(state.get().status())
        || state.get().error() == null
        || !state.get().error().startsWith("DEFERRED:")) {
      return;
    }
    if (deferredCount(producer) == 0) {
      recovery.mark(projection, producer, "READY", null, Instant.now());
    }
  }

  private int deferredCount(String producer) {
    Integer remaining = jdbc.queryForObject("""
        SELECT count(*) FROM processed_messages
        WHERE consumer_name=? AND producer=? AND status='DEFERRED'
        """, Integer.class, EVENT_CONSUMER, producer);
    return remaining == null ? 0 : remaining;
  }

  public static String projection(String producer) {
    return switch (producer) {
      case "appointments" -> "appointments";
      case "wash-administration" -> "wash-administration";
      case "authorization" -> "authorization";
      default -> throw new IllegalArgumentException("UNSUPPORTED_PROJECTION_PRODUCER:" + producer);
    };
  }
}
