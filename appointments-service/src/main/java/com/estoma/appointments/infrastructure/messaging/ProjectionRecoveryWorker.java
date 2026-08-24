package com.estoma.appointments.infrastructure.messaging;

import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.ProjectionRecoveryJobRepository;
import com.estoma.appointments.application.port.out.ProjectionResetRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.application.recovery.ProjectionRecoveryCoordinator;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Starts one durable authoritative rebuild per projection and never floods an in-flight job. */
@Component
@ConditionalOnProperty(prefix = "estoma.messaging", name = "recovery-worker-enabled", havingValue = "true")
public class ProjectionRecoveryWorker {
  private static final int PAGE_SIZE = 200;
  private final ProjectionRecoveryCoordinator recovery;
  private final ProjectionRecoveryJobRepository jobs;
  private final ProjectionResetRepository reset;
  private final WashAdministrationProjectionRepository administration;
  private final OutboxRepository outbox;
  private final Clock clock;
  private final TransactionTemplate transactions;

  public ProjectionRecoveryWorker(ProjectionRecoveryCoordinator recovery,
      ProjectionRecoveryJobRepository jobs, ProjectionResetRepository reset,
      WashAdministrationProjectionRepository administration, OutboxRepository outbox, Clock clock,
      PlatformTransactionManager transactionManager) {
    this.recovery = recovery;
    this.jobs = jobs;
    this.reset = reset;
    this.administration = administration;
    this.outbox = outbox;
    this.clock = clock;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "${estoma.messaging.recovery-poll-interval:30s}")
  public void scheduledBootstrap() {
    startIfNeeded("academic");
    startIfNeeded("authorization");
    startIfNeeded("wash-administration");
    if (recovery.isReady("wash-administration")) startIfNeeded("wash-execution");
  }

  /** Each owner rebuild starts atomically: claim + state + reset + first Query Outbox. */
  public void startIfNeeded(String projection) {
    transactions.executeWithoutResult(status -> startInCurrentTransaction(projection));
  }

  private void startInCurrentTransaction(String projection) {
    var state = recovery.state(projection);
    if ("READY".equals(state.status())) return;
    if (jobs.find(projection).isPresent()) return;

    Instant now = clock.instant();
    UUID generation = UUID.randomUUID();
    UUID queryId = UUID.randomUUID();
    QuerySeed seed = seed(projection, now);
    if (seed == null) return;

    var job = new ProjectionRecoveryJobRepository.RecoveryJob(projection, generation, seed.phase(),
        queryId, null, seed.contextJson(), now, now);
    if (!jobs.tryStart(job)) return; // another replica owns the generation

    recovery.rebuilding(projection);
    reset.reset(projection);
    appendQuery(queryId, generation, seed.messageType(), seed.payloadJson(), now);
  }

  private QuerySeed seed(String projection, Instant now) {
    return switch (projection) {
      case "academic" -> new QuerySeed("CONFIG", "GetAppointmentAcademicConfiguration", "{}", "{}");
      case "authorization" -> new QuerySeed("PAGE", "ListAccountAuthorizationSnapshotsPage",
          "{\"limit\":" + PAGE_SIZE + ",\"systemCode\":\"LAVADO_ULTRASONICO\"}",
          "{\"systemCode\":\"LAVADO_ULTRASONICO\"}");
      case "wash-administration" -> new QuerySeed("CONFIG", "GetWashAdministrationConfiguration", "{}", "{}");
      case "wash-execution" -> executionSeed(now);
      default -> throw new IllegalArgumentException("unknown projection " + projection);
    };
  }

  private QuerySeed executionSeed(Instant now) {
    var candidates = administration.findCalendars().stream()
        .filter(c -> "ACTIVE".equals(c.status()))
        .filter(c -> {
          LocalDate localDate = now.atZone(ZoneId.of(c.timezone())).toLocalDate();
          return !localDate.isBefore(c.effectiveFrom())
              && (c.effectiveUntil() == null || !localDate.isAfter(c.effectiveUntil()));
        }).toList();
    if (candidates.size() != 1) {
      recovery.degraded("wash-execution",
          "cannot derive a unique active service calendar for Execution recovery");
      return null;
    }
    var calendar = candidates.getFirst();
    LocalDate serviceDate = now.atZone(ZoneId.of(calendar.timezone())).toLocalDate();
    String date = serviceDate.toString();
    return new QuerySeed("PAGE", "ListWashExecutionIntegrationContextsPage",
        "{\"serviceDate\":\"" + date + "\",\"pageSize\":" + PAGE_SIZE + "}",
        "{\"serviceDate\":\"" + date + "\"}");
  }

  void appendQuery(UUID messageId, UUID correlationId, String messageType, String payload, Instant now) {
    outbox.append(new OutboxRepository.OutboxMessage(messageId, messageType, 1, "QUERY",
        null, null, correlationId, null, payload, now, now, "PENDING", 0, null, null));
  }

  static String pagePayload(String owner, String cursor, String contextJson) {
    if ("academic".equals(owner)) {
      String period = jsonString(contextJson, "academicPeriodId");
      return "{\"academicPeriodId\":\"" + period + "\",\"limit\":" + PAGE_SIZE
          + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
    }
    if ("authorization".equals(owner)) {
      return "{\"limit\":" + PAGE_SIZE + ",\"systemCode\":\"LAVADO_ULTRASONICO\""
          + (cursor == null ? "" : ",\"cursor\":\"" + cursor + "\"") + "}";
    }
    if ("wash-execution".equals(owner)) {
      String date = jsonString(contextJson, "serviceDate");
      return "{\"serviceDate\":\"" + date + "\",\"pageSize\":" + PAGE_SIZE
          + (cursor == null ? "" : ",\"pageToken\":\"" + cursor + "\"") + "}";
    }
    throw new IllegalArgumentException("owner is not paginated: " + owner);
  }

  private static String jsonString(String json, String field) {
    String marker = "\"" + field + "\":\"";
    int start = json.indexOf(marker);
    if (start < 0) throw new IllegalArgumentException("missing recovery context field " + field);
    start += marker.length();
    int end = json.indexOf('"', start);
    if (end < 0) throw new IllegalArgumentException("invalid recovery context");
    return json.substring(start, end);
  }

  private record QuerySeed(String phase, String messageType, String payloadJson, String contextJson) {}
}
