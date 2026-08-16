package com.estoma.academic.application;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.domain.AcademicEligibilityPolicy;
import com.estoma.academic.domain.StudentAcademicProfile;
import com.estoma.academic.messaging.EnvelopeCodec;
import com.estoma.academic.persistence.OutboxRepository;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;

/** Durable Process Manager for applying one validated FULL import. */
@Component
public class StudentImportApplicationManager {
  private static final Actor SYSTEM = new Actor("SYSTEM", null, "academic");
  private static final Set<String> LOCAL = Set.of(
      "REGISTER_COURSE_SECTION", "ACTIVATE_COURSE_SECTION", "DEACTIVATE_COURSE_SECTION",
      "CREATE_STUDENT_PROFILE", "CHANGE_STUDENT_SEMESTER", "ACTIVATE_STUDENT_PROFILE",
      "DEACTIVATE_STUDENT_PROFILE", "ADD_STUDENT_ENROLLMENT", "REACTIVATE_STUDENT_ENROLLMENT",
      "REMOVE_STUDENT_ENROLLMENT", "FINALIZE_STUDENT_IMPORT");

  private final JdbcClient jdbc;
  private final EnvelopeCodec codec;
  private final OutboxRepository outbox;
  private final AcademicEligibilityPolicyRegistry eligibilityPolicies;
  private final StudentImportPlanBuilder planBuilder;
  private final TransactionTemplate transactions;

  public StudentImportApplicationManager(
      JdbcClient jdbc,
      EnvelopeCodec codec,
      OutboxRepository outbox,
      AcademicEligibilityPolicyRegistry eligibilityPolicies,
      StudentImportPlanBuilder planBuilder,
      PlatformTransactionManager transactionManager) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.outbox = outbox;
    this.eligibilityPolicies = eligibilityPolicies;
    this.planBuilder = planBuilder;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "${estoma.student-import.application-delay:5s}")
  public void schedule() {
    List<UUID> validated = jdbc.sql("""
        SELECT import_id FROM student_import_processes
        WHERE status='VALIDATED' ORDER BY validated_at
        """).query(UUID.class).list();
    for (UUID importId : validated) {
      Boolean revalidationRequired = transactions.execute(status -> startValidated(importId));
      if (Boolean.TRUE.equals(revalidationRequired)) {
        planBuilder.buildIfReady(importId);
      }
    }

    List<UUID> applying = jdbc.sql("""
        SELECT import_id FROM student_import_processes
        WHERE status='APPLYING' ORDER BY application_started_at NULLS LAST
        """).query(UUID.class).list();
    for (UUID importId : applying) {
      transactions.executeWithoutResult(status -> runOne(importId, status));
    }
  }

  /** Returns true when the validated plan was stale and was reset for deterministic revalidation. */
  private boolean startValidated(UUID importId) {
    ApplicationProcess process = jdbc.sql("""
        SELECT p.academic_period_id,period.academic_data_status::text,period.version,
               p.process_version,p.execution_generation
        FROM student_import_processes p
        JOIN academic_periods period ON period.academic_period_id=p.academic_period_id
        WHERE p.import_id=:id AND p.status='VALIDATED'
        FOR UPDATE OF p,period
        """).param("id", importId)
        .query((rs, n) -> new ApplicationProcess(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getInt(3), rs.getInt(4), rs.getInt(5)))
        .optional().orElse(null);
    if (process == null) return false;

    Instant now = Instant.now();
    if (planIsStale(importId)) {
      jdbc.sql("DELETE FROM student_import_plan_items WHERE import_id=:id")
          .param("id", importId).update();
      int version = process.processVersion + 1;
      requireChanged(jdbc.sql("""
          UPDATE student_import_processes
          SET status='VALIDATING',process_version=:version,validated_at=NULL,
              failure_code='PLAN_STALE',failure_reason='Validated plan became stale before application',
              recoverable=true,updated_at=:now
          WHERE import_id=:id AND status='VALIDATED' AND process_version=:expected
          """).param("version", version).param("now", timestamp(now)).param("id", importId)
          .param("expected", process.processVersion).update());
      appendProcessEvent(importId, "StudentImportProgressUpdated", version,
          progressPayload(importId, process.periodId, "VALIDATING", now), now);
      return true;
    }

    int newGeneration = process.executionGeneration + 1;
    int periodVersion = process.periodVersion + 1;
    requireChanged(jdbc.sql("""
        UPDATE academic_periods
        SET academic_data_status='IMPORTING',version=:version,updated_at=:now
        WHERE academic_period_id=:id AND version=:expected
          AND academic_data_status IN ('PENDING_IMPORT','READY')
        """).param("version", periodVersion).param("now", timestamp(now))
        .param("id", process.periodId).param("expected", process.periodVersion).update());

    int startVersion = process.processVersion + 1;
    int progressVersion = process.processVersion + 2;
    requireChanged(jdbc.sql("""
        UPDATE student_import_processes
        SET status='APPLYING',process_version=:version,execution_generation=:generation,
            application_started_at=COALESCE(application_started_at,:now),
            failure_code=NULL,failure_reason=NULL,failed_step=NULL,recoverable=NULL,
            last_progress_at=:now,last_progress_completed_items=0,last_progress_percent=0,updated_at=:now
        WHERE import_id=:id AND status='VALIDATED' AND process_version=:expected
        """).param("version", progressVersion).param("generation", newGeneration)
        .param("now", timestamp(now)).param("id", importId).param("expected", process.processVersion).update());

    jdbc.sql("""
        UPDATE student_import_plan_items
        SET execution_generation=:generation,
            current_command_message_id=NULL,current_operation_id=NULL,current_idempotency_key=NULL,
            last_result_message_id=NULL,last_result_status=NULL,received_result=NULL,
            attempts=0,first_published_at=NULL,next_attempt_at=NULL,
            expected_versions=CASE WHEN item_type='FINALIZE_STUDENT_IMPORT'
              THEN jsonb_set(expected_versions,'{academicPeriodVersion}',to_jsonb(:periodVersion))
              ELSE expected_versions END,
            updated_at=:now
        WHERE import_id=:id AND status='PENDING'
        """).param("generation", newGeneration).param("periodVersion", periodVersion)
        .param("now", timestamp(now)).param("id", importId).update();

    appendProcessEvent(importId, "StudentImportApplicationStarted", startVersion,
        map("importId", importId, "academicPeriodId", process.periodId, "startedAt", now), now);
    appendProcessEvent(importId, "StudentImportProgressUpdated", progressVersion,
        progressPayload(importId, process.periodId, "APPLYING", now), now);
    appendPeriodEvent(importId, process.periodId, periodVersion,
        map("academicPeriodId", process.periodId,
            "previousAcademicDataStatus", process.periodDataStatus,
            "academicDataStatus", "IMPORTING",
            "reason", "IMPORT_APPLICATION_STARTED",
            "changedAt", now), now);
    return false;
  }

  private boolean planIsStale(UUID importId) {
    List<VersionCheck> checks = jdbc.sql("""
        SELECT item_type::text,planned_effect::text,expected_versions::text
        FROM student_import_plan_items WHERE import_id=:id ORDER BY created_at,plan_item_id
        """).param("id", importId)
        .query((rs, n) -> new VersionCheck(rs.getString(1), rs.getString(2), rs.getString(3))).list();
    Map<String, TargetVersion> initialTargets = new LinkedHashMap<>();
    for (VersionCheck check : checks) {
      JsonNode effect = codec.node(check.effect);
      JsonNode versions = codec.node(check.versions);
      TargetVersion target = targetVersion(check.type, effect, versions);
      if (target == null) continue;
      initialTargets.merge(target.targetKey, target,
          (current, candidate) -> current.expectedVersion <= candidate.expectedVersion ? current : candidate);
    }
    for (TargetVersion target : initialTargets.values()) {
      Integer actual = currentVersion(target);
      if (actual == null || actual != target.expectedVersion) return true;
    }
    return false;
  }

  private TargetVersion targetVersion(String type, JsonNode effect, JsonNode versions) {
    return switch (type) {
      case "UPDATE_PERSON" -> target("person", uuid(effect, "personId"), versions, "personVersion", "person_status_projection", "person_id");
      case "ACTIVATE_ACCOUNT", "DEACTIVATE_ACCOUNT" -> target("account", uuid(effect, "accountId"), versions, "accountVersion", "account_status_projection", "account_id");
      case "ASSIGN_STUDENT_ROLE" -> target("role", uuid(effect, "accountId"), versions, "roleVersion", "authorization_context_projection", "account_id");
      case "GRANT_STUDENT_SYSTEM_ACCESS" -> target("access", uuid(effect, "accountId"), versions, "systemAccessVersion", "system_access_context_projection", "account_id");
      case "CHANGE_STUDENT_SEMESTER", "ACTIVATE_STUDENT_PROFILE", "DEACTIVATE_STUDENT_PROFILE" ->
          target("profile", uuid(effect, "studentAcademicProfileId"), versions, "studentAcademicProfileVersion", "student_academic_profiles", "student_academic_profile_id");
      case "ACTIVATE_COURSE_SECTION", "DEACTIVATE_COURSE_SECTION" ->
          target("course", uuid(effect, "courseSectionId"), versions, "courseSectionVersion", "course_sections", "course_section_id");
      case "REACTIVATE_STUDENT_ENROLLMENT", "REMOVE_STUDENT_ENROLLMENT" ->
          target("enrollment", uuid(effect, "enrollmentId"), versions, "enrollmentVersion", "student_course_sections", "enrollment_id");
      case "FINALIZE_STUDENT_IMPORT" ->
          target("period", uuid(effect, "academicPeriodId"), versions, "academicPeriodVersion", "academic_periods", "academic_period_id");
      default -> null;
    };
  }

  private TargetVersion target(
      String kind, UUID id, JsonNode versions, String versionField, String table, String idColumn) {
    if (id == null || !versions.has(versionField) || !versions.path(versionField).canConvertToInt()) return null;
    return new TargetVersion(kind + ":" + id, id, versions.path(versionField).asInt(), table, idColumn, kind);
  }

  private Integer currentVersion(TargetVersion target) {
    String column = switch (target.kind) {
      case "person", "account", "role", "access" -> "source_version";
      default -> "version";
    };
    String extra = switch (target.kind) {
      case "role" -> " AND role_code='ALUMNO'";
      case "access" -> " AND system_code='LAVADO_ULTRASONICO'";
      default -> "";
    };
    return jdbc.sql("SELECT " + column + " FROM " + target.table + " WHERE " + target.idColumn + "=:id" + extra)
        .param("id", target.id).query(Integer.class).optional().orElse(null);
  }

  private void runOne(UUID importId, TransactionStatus transactionStatus) {
    if (!lockApplyingProcess(importId)) return;
    Instant now = Instant.now();
    Item processing = processingItem(importId);
    if (processing != null) {
      if (processing.nextAttemptAt != null && processing.nextAttemptAt.isAfter(now)) return;
      if (processing.attempts >= 5 || firstPublishedOverThirtyMinutesAgo(importId, processing.id)) {
        fail(importId, processing, "RESULT_TIMEOUT", null, null, now);
      } else {
        requireChanged(jdbc.sql("""
            UPDATE student_import_plan_items SET status='PENDING',next_attempt_at=:now,updated_at=:now
            WHERE import_id=:import AND plan_item_id=:item AND status='PROCESSING'
            """).param("now", timestamp(now)).param("import", importId).param("item", processing.id).update());
        publishRemote(importId, processing, now);
      }
      return;
    }

    Item next = pendingItem(importId);
    if (next == null || ("FINALIZE_STUDENT_IMPORT".equals(next.type) && outstanding(importId))) return;
    if (LOCAL.contains(next.type)) {
      Object savepoint = transactionStatus.createSavepoint();
      try {
        executeLocal(importId, next, now);
        complete(importId, next, null, null, null, now);
        transactionStatus.releaseSavepoint(savepoint);
      } catch (RuntimeException ex) {
        transactionStatus.rollbackToSavepoint(savepoint);
        fail(importId, next, "LOCAL_APPLICATION_FAILED", null, null, now);
      }
    } else {
      publishRemote(importId, next, now);
    }
  }

  private boolean lockApplyingProcess(UUID importId) {
    return jdbc.sql("""
        SELECT EXISTS (
          SELECT 1 FROM student_import_processes
          WHERE import_id=:id AND status='APPLYING' FOR UPDATE)
        """).param("id", importId).query(Boolean.class).single();
  }

  private String lockResultProcess(UUID importId) {
    return jdbc.sql("""
        SELECT status::text FROM student_import_processes
        WHERE import_id=:id AND status IN ('APPLYING','APPLICATION_FAILED')
        FOR UPDATE
        """).param("id", importId).query(String.class).optional().orElse(null);
  }

  private Item processingItem(UUID importId) {
    return item(importId, "PROCESSING", "ORDER BY i.updated_at LIMIT 1");
  }

  private Item pendingItem(UUID importId) {
    return item(importId, "PENDING", """
        ORDER BY CASE
          WHEN item_type IN ('REGISTER_COURSE_SECTION','ACTIVATE_COURSE_SECTION') THEN 1
          WHEN item_type IN ('CREATE_PERSON','UPDATE_PERSON') THEN 2
          WHEN item_type IN ('CREATE_ACCOUNT','ACTIVATE_ACCOUNT') THEN 3
          WHEN item_type='ASSIGN_STUDENT_ROLE' THEN 4
          WHEN item_type IN ('CREATE_STUDENT_PROFILE','ACTIVATE_STUDENT_PROFILE') THEN 5
          WHEN item_type='CHANGE_STUDENT_SEMESTER' THEN 6
          WHEN item_type='GRANT_STUDENT_SYSTEM_ACCESS' THEN 7
          WHEN item_type IN ('ADD_STUDENT_ENROLLMENT','REACTIVATE_STUDENT_ENROLLMENT') THEN 8
          WHEN item_type='REMOVE_STUDENT_ENROLLMENT' THEN 9
          WHEN item_type='DEACTIVATE_COURSE_SECTION' THEN 10
          WHEN item_type='DEACTIVATE_STUDENT_PROFILE' THEN 11
          WHEN item_type='DEACTIVATE_ACCOUNT' THEN 12
          WHEN item_type='FINALIZE_STUDENT_IMPORT' THEN 13
          ELSE 50 END,
          i.created_at,i.plan_item_id
        LIMIT 1
        """);
  }

  private Item item(UUID importId, String status, String ordering) {
    return jdbc.sql("""
        SELECT i.plan_item_id,i.item_type::text,i.business_key::text,i.planned_effect::text,i.expected_versions::text,
          i.execution_generation,i.attempts,i.current_command_message_id,i.current_operation_id,i.current_idempotency_key,
          i.last_result_message_id,i.last_result_status,i.next_attempt_at,
          p.authorized_by_account_id,p.process_version,i.status::text
        FROM student_import_plan_items i
        JOIN student_import_processes p ON p.import_id=i.import_id
        WHERE i.import_id=:id AND p.status='APPLYING'
          AND i.status=CAST(:status AS student_import_plan_item_status)
        %s
        """.formatted(ordering)).param("id", importId).param("status", status)
        .query((rs, n) -> new Item(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getInt(6), rs.getInt(7), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class), rs.getString(10),
            rs.getObject(11, UUID.class), rs.getString(12),
            rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(),
            rs.getObject(14, UUID.class), rs.getInt(15), rs.getString(16)))
        .optional().orElse(null);
  }

  private boolean firstPublishedOverThirtyMinutesAgo(UUID importId, UUID itemId) {
    return jdbc.sql("""
        SELECT COALESCE(first_published_at <= now()-interval '30 minutes',false)
        FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item
        """).param("import", importId).param("item", itemId).query(Boolean.class).single();
  }

  private void publishRemote(UUID importId, Item item, Instant now) {
    UUID messageId = UUID.randomUUID();
    UUID operationId = item.operationId == null ? UUID.randomUUID() : item.operationId;
    String idempotencyKey = item.idempotencyKey == null
        ? "student-import:" + importId + ":" + item.id + ":" + item.generation
        : item.idempotencyKey;
    JsonNode effect = codec.node(item.effect);
    Map<String, Object> payload = new LinkedHashMap<>();
    effect.properties().forEach(entry -> payload.put(entry.getKey(), entry.getValue()));
    addExpectedVersion(item, payload);
    if ("DEACTIVATE_ACCOUNT".equals(item.type)) payload.putIfAbsent("reasonCode", "ACADEMIC_ABSENCE");

    List<Map<String, Object>> dependencies = causalDependencies(importId, item, effect);
    List<Map<String, Object>> evidence = authoritativeEvidence(importId, item, effect, dependencies);
    payload.put("importExecution", map(
        "importId", importId,
        "planItemId", item.id,
        "executionGeneration", item.generation,
        "authorizedByAccountId", item.authorizedBy,
        "scope", item.type,
        "causalDependencies", dependencies,
        "authoritativeEvidence", evidence));

    UUID causationId = dependencies.isEmpty() ? null
        : UUID.fromString(String.valueOf(dependencies.getFirst().get("priorResultMessageId")));
    outbox.append(messageId, command(item.type), "COMMAND", targetAggregateId(item.type, effect),
        targetAggregateVersion(item), importId, causationId, codec.json(payload),
        codec.json(map("destination", destination(item.type), "operationId", operationId,
            "idempotencyKey", idempotencyKey, "actor", SYSTEM)), now);

    requireChanged(jdbc.sql("""
        UPDATE student_import_plan_items
        SET status='PROCESSING',current_command_message_id=:message,current_operation_id=:operation,
            current_idempotency_key=:key,attempts=attempts+1,
            first_published_at=COALESCE(first_published_at,:now),next_attempt_at=:next,updated_at=:now
        WHERE import_id=:import AND plan_item_id=:item AND status='PENDING' AND execution_generation=:generation
        """).param("message", messageId).param("operation", operationId).param("key", idempotencyKey)
        .param("now", timestamp(now)).param("next", timestamp(now.plusSeconds(retryDelay(item.attempts))))
        .param("import", importId).param("item", item.id).param("generation", item.generation).update());
  }

  private void addExpectedVersion(Item item, Map<String, Object> payload) {
    JsonNode versions = codec.node(item.versions);
    switch (item.type) {
      case "UPDATE_PERSON" -> putExpected(payload, versions, "personVersion");
      case "ACTIVATE_ACCOUNT", "DEACTIVATE_ACCOUNT" -> putExpected(payload, versions, "accountVersion");
      default -> { }
    }
  }

  private static void putExpected(Map<String, Object> payload, JsonNode versions, String field) {
    if (versions.has(field) && versions.path(field).canConvertToInt()) {
      payload.put("expectedVersion", versions.path(field).asInt());
    }
  }

  private List<Map<String, Object>> causalDependencies(UUID importId, Item item, JsonNode effect) {
    PlanResult prior = switch (item.type) {
      case "CREATE_ACCOUNT" -> completedPrior(importId, List.of("CREATE_PERSON"), "personId", uuid(effect, "personId"));
      case "ASSIGN_STUDENT_ROLE" -> completedPrior(importId, List.of("CREATE_ACCOUNT", "ACTIVATE_ACCOUNT"), "accountId", uuid(effect, "accountId"));
      case "GRANT_STUDENT_SYSTEM_ACCESS" -> completedPrior(importId, List.of("ASSIGN_STUDENT_ROLE"), "accountId", uuid(effect, "accountId"));
      default -> null;
    };
    if (prior == null || prior.resultMessageId == null || !"SUCCEEDED".equals(prior.resultStatus)) return List.of();
    String expectedEvent = switch (prior.type) {
      case "CREATE_PERSON" -> "PersonCreated";
      case "CREATE_ACCOUNT" -> "AccountCreated";
      case "ACTIVATE_ACCOUNT" -> "AccountActivated";
      case "ASSIGN_STUDENT_ROLE" -> "AccountRoleAssigned";
      default -> throw new IllegalStateException("unsupported causal predecessor " + prior.type);
    };
    UUID aggregateId = switch (prior.type) {
      case "CREATE_PERSON" -> uuid(codec.node(prior.effect), "personId");
      default -> uuid(codec.node(prior.effect), "accountId");
    };
    return List.of(map(
        "priorCommandType", command(prior.type),
        "priorResultMessageId", prior.resultMessageId,
        "priorResultStatus", "SUCCEEDED",
        "expectedEventType", expectedEvent,
        "expectedAggregateId", aggregateId));
  }

  private List<Map<String, Object>> authoritativeEvidence(
      UUID importId, Item item, JsonNode effect, List<Map<String, Object>> dependencies) {
    if (!"CREATE_ACCOUNT".equals(item.type) || !dependencies.isEmpty()) return List.of();
    UUID personId = uuid(effect, "personId");
    if (personId == null) return List.of();
    Evidence evidence = jdbc.sql("""
        SELECT result->>'sourceResultMessageId'
        FROM student_import_validation_work
        WHERE import_id=:import AND work_type='AUTHORITATIVE_PERSON_RESOLUTION'
          AND status='SUCCEEDED' AND result->>'outcome'='FOUND'
          AND result->>'personId'=:person
        LIMIT 1
        """).param("import", importId).param("person", personId.toString())
        .query((rs, n) -> new Evidence(rs.getString(1))).optional().orElse(null);
    if (evidence == null) return List.of();
    Map<String, Object> value = map(
        "sourceService", "users",
        "sourceOperation", "ResolvePersonByInstitutionalEmail",
        "sourceResultStatus", "FOUND",
        "entityType", "Person",
        "entityId", personId);
    if (evidence.resultMessageId != null && !evidence.resultMessageId.isBlank()) {
      value.put("sourceResultMessageId", UUID.fromString(evidence.resultMessageId));
    }
    return List.of(value);
  }

  private PlanResult completedPrior(
      UUID importId, List<String> types, String effectField, UUID targetId) {
    if (targetId == null) return null;
    String placeholders = String.join(",", types.stream().map(value -> "'" + value + "'").toList());
    return jdbc.sql("""
        SELECT item_type::text,planned_effect::text,last_result_message_id,last_result_status
        FROM student_import_plan_items
        WHERE import_id=:import AND status='COMPLETED'
          AND item_type::text IN (%s)
          AND planned_effect->>:field=:target
        ORDER BY updated_at DESC LIMIT 1
        """.formatted(placeholders))
        .param("import", importId).param("field", effectField).param("target", targetId.toString())
        .query((rs, n) -> new PlanResult(
            rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), rs.getString(4)))
        .optional().orElse(null);
  }

  private UUID targetAggregateId(String type, JsonNode effect) {
    return switch (type) {
      case "CREATE_PERSON", "UPDATE_PERSON" -> uuid(effect, "personId");
      case "CREATE_ACCOUNT", "ACTIVATE_ACCOUNT", "DEACTIVATE_ACCOUNT", "ASSIGN_STUDENT_ROLE" -> uuid(effect, "accountId");
      case "GRANT_STUDENT_SYSTEM_ACCESS" -> uuid(effect, "systemAccessId");
      default -> null;
    };
  }

  private Integer targetAggregateVersion(Item item) {
    JsonNode versions = codec.node(item.versions);
    return switch (item.type) {
      case "UPDATE_PERSON" -> integerOrNull(versions, "personVersion");
      case "ACTIVATE_ACCOUNT", "DEACTIVATE_ACCOUNT" -> integerOrNull(versions, "accountVersion");
      case "ASSIGN_STUDENT_ROLE" -> integerOrNull(versions, "roleVersion");
      case "GRANT_STUDENT_SYSTEM_ACCESS" -> integerOrNull(versions, "systemAccessVersion");
      default -> null;
    };
  }

  private static Integer integerOrNull(JsonNode node, String field) {
    return node.has(field) && node.path(field).canConvertToInt() ? node.path(field).asInt() : null;
  }

  @Transactional
  public boolean applyResult(MessageEnvelope result) {
     if (result == null || result.messageVersion() != 1 || !"RESULT".equals(result.messageKind()) || result.messageId() == null
        || result.operationId() == null || result.idempotencyKey() == null
        || result.correlationId() == null || result.payload() == null || !result.payload().isObject()) {
      throw new IllegalArgumentException("invalid result");
    }
    if (jdbc.sql("""
        INSERT INTO processed_messages (
          consumer_name,message_id,correlation_id,message_type,message_kind,producer,
          aggregate_id,aggregate_version,envelope,processing_state,reconciliation_state,processed_at)
        VALUES ('academic.import-results',:id,:correlation,:type,:kind,:producer,:aggregate,:version,
          CAST(:envelope AS jsonb),'APPLIED',NULL,now())
        ON CONFLICT DO NOTHING
        """).param("id", result.messageId()).param("correlation", result.correlationId())
        .param("type", result.messageType()).param("kind", result.messageKind()).param("producer", result.producer())
        .param("aggregate", result.aggregateId()).param("version", result.aggregateVersion())
        .param("envelope", codec.json(result)).update() == 0) return false;

    JsonNode data = result.payload().path("data");
    if (!data.isObject()) return false;
    UUID importId = uuid(data, "importId");
    UUID itemId = uuid(data, "planItemId");
    if (importId == null || itemId == null) return false;
    String processStatus = lockResultProcess(importId);
    if (processStatus == null) return false;
    Item item = resultItem(importId, itemId);
    if (item == null) return false;
    boolean applying = "APPLYING".equals(processStatus) && "PROCESSING".equals(item.status);
    boolean lateTimeout = "APPLICATION_FAILED".equals(processStatus)
        && "FAILED".equals(item.status) && timeoutFailure(importId, item.id);
    if (!applying && !lateTimeout) return false;
    if (!Objects.equals(result.correlationId(), importId)
        || result.causationId() == null
        || !Objects.equals(result.operationId(), item.operationId)
        || !Objects.equals(result.idempotencyKey(), item.idempotencyKey)
        || item.generation != data.path("executionGeneration").asInt(-1)
        || !result.messageType().equals(command(item.type) + "Result")
        || !destination(item.type).equals(result.producer())
        || !knownCommandAttempt(result, importId, item)) return false;

    Instant now = Instant.now();
    String outcome = result.payload().path("status").asString();
    if (lateTimeout) {
      reconcileLateTimeout(importId, item, result, outcome, now);
      return true;
    }
    switch (outcome) {
      case "SUCCEEDED" -> complete(importId, item, codec.json(result.payload()), result.messageId(), outcome, now);
      case "REJECTED" -> fail(importId, item,
          result.payload().path("error").path("code").asString("REMOTE_REJECTED"),
          codec.json(result.payload()), result.messageId(), now);
      case "FAILED", "EXPIRED" -> fail(importId, item,
          result.payload().path("error").path("code").asString("REMOTE_" + outcome),
          codec.json(result.payload()), result.messageId(), now);
      default -> throw new IllegalArgumentException("invalid terminal result status");
    }
    return true;
  }

  private boolean timeoutFailure(UUID importId, UUID itemId) {
    return jdbc.sql("""
        SELECT last_error_code='RESULT_TIMEOUT' FROM student_import_plan_items
        WHERE import_id=:import AND plan_item_id=:item
        """).param("import", importId).param("item", itemId).query(Boolean.class).single();
  }

  private boolean knownCommandAttempt(MessageEnvelope result, UUID importId, Item item) {
    return jdbc.sql("""
        SELECT EXISTS (
          SELECT 1 FROM outbox_messages
          WHERE message_id=:causation AND message_kind='COMMAND' AND message_type=:type
            AND correlation_id=:import
            AND payload->'importExecution'->>'planItemId'=:item
            AND payload->'importExecution'->>'executionGeneration'=:generation
            AND metadata->>'operationId'=:operation
            AND metadata->>'idempotencyKey'=:key)
        """).param("causation", result.causationId()).param("type", command(item.type))
        .param("import", importId).param("item", item.id.toString())
        .param("generation", Integer.toString(item.generation))
        .param("operation", result.operationId().toString()).param("key", result.idempotencyKey())
        .query(Boolean.class).single();
  }

  private void reconcileLateTimeout(
      UUID importId, Item item, MessageEnvelope result, String outcome, Instant now) {
    String payload = codec.json(result.payload());
    switch (outcome) {
      case "SUCCEEDED" -> requireChanged(jdbc.sql("""
          UPDATE student_import_plan_items
          SET status='COMPLETED',received_result=CAST(:result AS jsonb),
              last_result_message_id=:message,last_result_status='SUCCEEDED',
              applied_aggregate_version=COALESCE(CAST(:aggregateVersion AS integer),applied_aggregate_version),
              last_error_code=NULL,last_error_at=NULL,updated_at=:now
          WHERE import_id=:import AND plan_item_id=:item AND status='FAILED'
            AND last_error_code='RESULT_TIMEOUT' AND execution_generation=:generation
          """).param("result", payload).param("message", result.messageId())
          .param("aggregateVersion", result.aggregateVersion()).param("now", timestamp(now))
          .param("import", importId).param("item", item.id).param("generation", item.generation).update());
      case "REJECTED", "FAILED", "EXPIRED" -> {
        String error = result.payload().path("error").path("code")
            .asString("REJECTED".equals(outcome) ? "REMOTE_REJECTED" : "REMOTE_" + outcome);
        requireChanged(jdbc.sql("""
            UPDATE student_import_plan_items
            SET received_result=CAST(:result AS jsonb),last_result_message_id=:message,
                last_result_status=:status,last_error_code=:error,last_error_at=:now,updated_at=:now
            WHERE import_id=:import AND plan_item_id=:item AND status='FAILED'
              AND last_error_code='RESULT_TIMEOUT' AND execution_generation=:generation
            """).param("result", payload).param("message", result.messageId()).param("status", outcome)
            .param("error", error).param("now", timestamp(now)).param("import", importId)
            .param("item", item.id).param("generation", item.generation).update());
      }
      default -> throw new IllegalArgumentException("invalid terminal result status");
    }
  }

  private Item resultItem(UUID importId, UUID itemId) {
    return jdbc.sql("""
        SELECT i.plan_item_id,i.item_type::text,i.business_key::text,i.planned_effect::text,i.expected_versions::text,
          i.execution_generation,i.attempts,i.current_command_message_id,i.current_operation_id,i.current_idempotency_key,
          i.last_result_message_id,i.last_result_status,i.next_attempt_at,
          p.authorized_by_account_id,p.process_version,i.status::text
        FROM student_import_plan_items i
        JOIN student_import_processes p ON p.import_id=i.import_id
        WHERE i.import_id=:import AND i.plan_item_id=:item FOR UPDATE
        """).param("import", importId).param("item", itemId)
        .query((rs, n) -> new Item(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
            rs.getInt(6), rs.getInt(7), rs.getObject(8, UUID.class), rs.getObject(9, UUID.class), rs.getString(10),
            rs.getObject(11, UUID.class), rs.getString(12),
            rs.getTimestamp(13) == null ? null : rs.getTimestamp(13).toInstant(),
            rs.getObject(14, UUID.class), rs.getInt(15), rs.getString(16)))
        .optional().orElse(null);
  }

  private void executeLocal(UUID importId, Item item, Instant now) {
    JsonNode effect = codec.node(item.effect);
    switch (item.type) {
      case "REGISTER_COURSE_SECTION" -> registerCourseSection(importId, item, effect, now);
      case "ACTIVATE_COURSE_SECTION", "DEACTIVATE_COURSE_SECTION" -> changeCourseSection(importId, item, effect, now);
      case "CREATE_STUDENT_PROFILE" -> createProfile(importId, item, effect, now);
      case "CHANGE_STUDENT_SEMESTER" -> changeSemester(importId, item, effect, now);
      case "ACTIVATE_STUDENT_PROFILE", "DEACTIVATE_STUDENT_PROFILE" -> changeProfileStatus(importId, item, effect, now);
      case "ADD_STUDENT_ENROLLMENT" -> addEnrollment(importId, item, effect, now);
      case "REACTIVATE_STUDENT_ENROLLMENT", "REMOVE_STUDENT_ENROLLMENT" -> changeEnrollment(importId, item, effect, now);
      case "FINALIZE_STUDENT_IMPORT" -> { }
      default -> throw new IllegalArgumentException("unsupported local item " + item.type);
    }
  }

  private void registerCourseSection(UUID importId, Item item, JsonNode effect, Instant now) {
    UUID id = uuid(effect, "courseSectionId");
    UUID periodId = uuid(effect, "academicPeriodId");
    String nrc = text(effect, "nrc");
    String name = text(effect, "name");
    requireChanged(jdbc.sql("""
        INSERT INTO course_sections (
          course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at)
        VALUES (:id,:period,:nrc,:name,'ACTIVE',1,:now,:now)
        """).param("id", id).param("period", periodId).param("nrc", nrc).param("name", name)
        .param("now", timestamp(now)).update());
    appendDomainEvent(importId, "CourseSectionRegistered", id, 1,
        map("courseSectionId", id, "academicPeriodId", periodId, "nrc", nrc,
            "name", name, "status", "ACTIVE", "registeredAt", now), now);
  }

  private void changeCourseSection(UUID importId, Item item, JsonNode effect, Instant now) {
    UUID id = uuid(effect, "courseSectionId");
    Section section = section(id);
    int expected = expectedVersion(item, "courseSectionVersion");
    String target = "ACTIVATE_COURSE_SECTION".equals(item.type) ? "ACTIVE" : "INACTIVE";
    requireChanged(jdbc.sql("""
        UPDATE course_sections SET status=CAST(:status AS course_section_status),version=version+1,updated_at=:now
        WHERE course_section_id=:id AND version=:expected
        """).param("status", target).param("now", timestamp(now)).param("id", id).param("expected", expected).update());
    appendDomainEvent(importId, "CourseSectionStatusChanged", id, expected + 1,
        map("courseSectionId", id, "academicPeriodId", section.periodId, "nrc", section.nrc,
            "previousStatus", section.status, "currentStatus", target,
            "changedByAccountId", item.authorizedBy, "reason", "STUDENT_IMPORT", "changedAt", now), now);
  }

  private void createProfile(UUID importId, Item item, JsonNode effect, Instant now) {
    UUID id = uuid(effect, "studentAcademicProfileId");
    UUID personId = uuid(effect, "personId");
    UUID accountId = uuid(effect, "accountId");
    String studentNumber = text(effect, "studentNumber");
    int semester = effect.path("currentSemester").asInt();
    requireChanged(jdbc.sql("""
        INSERT INTO student_academic_profiles (
          student_academic_profile_id,person_id,account_id,student_number,student_number_normalized,
          current_semester,academic_status,version,student_number_version,created_at,updated_at)
        VALUES (:id,:person,:account,:number,:normalized,:semester,'ACTIVE',1,0,:now,:now)
        """).param("id", id).param("person", personId).param("account", accountId)
        .param("number", studentNumber.trim())
        .param("normalized", StudentAcademicProfile.normalizedStudentNumber(studentNumber))
        .param("semester", semester).param("now", timestamp(now)).update());
    appendDomainEvent(importId, "StudentAcademicProfileCreated", id, 1,
        map("studentAcademicProfileId", id, "personId", personId, "accountId", accountId,
            "studentNumber", studentNumber, "currentSemester", semester,
            "academicStatus", "ACTIVE", "createdAt", now), now);
    evaluateEligibility(importId, id, accountId, semester, "ACTIVE", 1, now);
  }

  private void changeSemester(UUID importId, Item item, JsonNode effect, Instant now) {
    UUID id = uuid(effect, "studentAcademicProfileId");
    Profile profile = profile(id);
    int expected = expectedVersion(item, "studentAcademicProfileVersion");
    int semester = effect.path("currentSemester").asInt();
    requireChanged(jdbc.sql("""
        UPDATE student_academic_profiles SET current_semester=:semester,version=version+1,updated_at=:now
        WHERE student_academic_profile_id=:id AND version=:expected
        """).param("semester", semester).param("now", timestamp(now)).param("id", id).param("expected", expected).update());
    appendDomainEvent(importId, "StudentSemesterChanged", id, expected + 1,
        map("studentAcademicProfileId", id, "accountId", profile.accountId,
            "previousSemester", profile.semester, "currentSemester", semester, "changedAt", now), now);
    evaluateEligibility(importId, id, profile.accountId, semester, profile.status, expected + 1, now);
  }

  private void changeProfileStatus(UUID importId, Item item, JsonNode effect, Instant now) {
    UUID id = uuid(effect, "studentAcademicProfileId");
    Profile profile = profile(id);
    int expected = expectedVersion(item, "studentAcademicProfileVersion");
    String target = "ACTIVATE_STUDENT_PROFILE".equals(item.type) ? "ACTIVE" : "INACTIVE";
    requireChanged(jdbc.sql("""
        UPDATE student_academic_profiles
        SET academic_status=CAST(:status AS academic_profile_status),version=version+1,updated_at=:now
        WHERE student_academic_profile_id=:id AND version=:expected
        """).param("status", target).param("now", timestamp(now)).param("id", id).param("expected", expected).update());
    String eventType = "ACTIVE".equals(target)
        ? "StudentAcademicProfileActivated" : "StudentAcademicProfileDeactivated";
    Map<String, Object> payload = map(
        "studentAcademicProfileId", id, "accountId", profile.accountId,
        "previousStatus", profile.status, "academicStatus", target,
        "ACTIVE".equals(target) ? "activatedAt" : "deactivatedAt", now);
    if ("INACTIVE".equals(target)) payload.put("reason", "ACADEMIC_ABSENCE");
    appendDomainEvent(importId, eventType, id, expected + 1, payload, now);
    evaluateEligibility(importId, id, profile.accountId, profile.semester, target, expected + 1, now);
  }

  private void addEnrollment(UUID importId, Item item, JsonNode effect, Instant now) {
    UUID enrollmentId = uuid(effect, "enrollmentId");
    UUID profileId = uuid(effect, "studentAcademicProfileId");
    UUID sectionId = uuid(effect, "courseSectionId");
    Profile profile = profile(profileId);
    Section section = section(sectionId);
    requireChanged(jdbc.sql("""
        INSERT INTO student_course_sections (
          enrollment_id,student_academic_profile_id,course_section_id,status,enrolled_at,version,updated_at)
        VALUES (:id,:student,:section,'ACTIVE',:now,1,:now)
        """).param("id", enrollmentId).param("student", profileId).param("section", sectionId)
        .param("now", timestamp(now)).update());
    appendDomainEvent(importId, "StudentEnrolledInCourseSection", enrollmentId, 1,
        map("studentAcademicProfileId", profileId, "accountId", profile.accountId,
            "courseSectionId", sectionId, "academicPeriodId", section.periodId,
            "nrc", section.nrc, "enrolledAt", now), now);
  }

  private void changeEnrollment(UUID importId, Item item, JsonNode effect, Instant now) {
    UUID enrollmentId = uuid(effect, "enrollmentId");
    Enrollment enrollment = enrollment(enrollmentId);
    Profile profile = profile(enrollment.profileId);
    Section section = section(enrollment.sectionId);
    int expected = expectedVersion(item, "enrollmentVersion");
    boolean reactivate = "REACTIVATE_STUDENT_ENROLLMENT".equals(item.type);
    String target = reactivate ? "ACTIVE" : "REMOVED";
    requireChanged(jdbc.sql("""
        UPDATE student_course_sections
        SET status=CAST(:status AS enrollment_status),
            removed_at=:removed,version=version+1,updated_at=:now
        WHERE enrollment_id=:id AND version=:expected
        """).param("status", target).param("removed", reactivate ? null : timestamp(now))
        .param("now", timestamp(now)).param("id", enrollmentId).param("expected", expected).update());
    String eventType = reactivate ? "StudentEnrollmentReactivated" : "StudentRemovedFromCourseSection";
    appendDomainEvent(importId, eventType, enrollmentId, expected + 1,
        map("studentAcademicProfileId", enrollment.profileId, "accountId", profile.accountId,
            "courseSectionId", enrollment.sectionId, "academicPeriodId", section.periodId,
            "nrc", section.nrc, reactivate ? "reactivatedAt" : "removedAt", now), now);
  }

  private void evaluateEligibility(
      UUID importId, UUID profileId, UUID accountId, int semester, String status, int profileVersion, Instant now) {
    AcademicEligibilityPolicy policy = eligibilityPolicies.active();
    boolean eligible = policy.isEligible(new StudentAcademicProfile(
        profileId, profileId, accountId, "evaluation", semester,
        StudentAcademicProfile.Status.valueOf(status), profileVersion));
    Eligibility old = jdbc.sql("""
        SELECT eligibility_id,eligible,policy_version,eligibility_version
        FROM student_academic_eligibilities
        WHERE student_academic_profile_id=:profile AND system_code=:system FOR UPDATE
        """).param("profile", profileId).param("system", policy.systemCode())
        .query((rs, n) -> new Eligibility(
            rs.getObject(1, UUID.class), rs.getBoolean(2), rs.getInt(3), rs.getInt(4)))
        .optional().orElse(null);
    if (old != null && old.eligible == eligible && old.policyVersion == policy.version()) return;
    UUID eligibilityId = old == null ? UUID.randomUUID() : old.id;
    int eligibilityVersion = old == null ? 1 : old.version + 1;
    if (old == null) {
      jdbc.sql("""
          INSERT INTO student_academic_eligibilities (
            eligibility_id,student_academic_profile_id,system_code,eligible,policy_version,
            eligibility_version,academic_profile_version,evaluated_at,updated_at)
          VALUES (:id,:profile,:system,:eligible,:policy,:version,:profileVersion,:now,:now)
          """).param("id", eligibilityId).param("profile", profileId).param("system", policy.systemCode())
          .param("eligible", eligible).param("policy", policy.version()).param("version", eligibilityVersion)
          .param("profileVersion", profileVersion).param("now", timestamp(now)).update();
    } else {
      requireChanged(jdbc.sql("""
          UPDATE student_academic_eligibilities
          SET eligible=:eligible,policy_version=:policy,eligibility_version=:version,
              academic_profile_version=:profileVersion,evaluated_at=:now,updated_at=:now
          WHERE eligibility_id=:id AND eligibility_version=:expected
          """).param("eligible", eligible).param("policy", policy.version()).param("version", eligibilityVersion)
          .param("profileVersion", profileVersion).param("now", timestamp(now))
          .param("id", eligibilityId).param("expected", old.version).update());
    }
    appendDomainEvent(importId, "StudentAcademicEligibilityChanged", eligibilityId, eligibilityVersion,
        map("eligibilityId", eligibilityId, "studentAcademicProfileId", profileId,
            "accountId", accountId, "systemCode", policy.systemCode(), "eligible", eligible,
            "policyVersion", policy.version(), "eligibilityVersion", eligibilityVersion,
            "academicProfileVersion", profileVersion, "evaluatedAt", now), now);
  }

  private void complete(
      UUID importId, Item item, String result, UUID resultMessageId, String resultStatus, Instant now) {
    if ("FINALIZE_STUDENT_IMPORT".equals(item.type)) finalizeImport(importId, item, now);
    requireChanged(jdbc.sql("""
        UPDATE student_import_plan_items
        SET status='COMPLETED',received_result=COALESCE(CAST(:result AS jsonb),received_result),
            last_result_message_id=COALESCE(:resultMessage,last_result_message_id),
            last_result_status=COALESCE(:resultStatus,last_result_status),
            applied_aggregate_version=COALESCE(applied_aggregate_version,CAST(:aggregateVersion AS integer)),updated_at=:now
        WHERE import_id=:import AND plan_item_id=:item AND status IN ('PENDING','PROCESSING')
        """).param("result", result).param("resultMessage", resultMessageId).param("resultStatus", resultStatus)
        .param("aggregateVersion", null).param("now", timestamp(now)).param("import", importId).param("item", item.id).update());
    if (!"FINALIZE_STUDENT_IMPORT".equals(item.type)) maybeProgress(importId, now);
  }

  private void finalizeImport(UUID importId, Item item, Instant now) {
    if (outstanding(importId)) throw new IllegalStateException("student import still has outstanding plan items");
    PeriodState period = periodState(importId);
    int expectedPeriod = expectedVersion(item, "academicPeriodVersion");
    int newPeriodVersion = expectedPeriod + 1;
    requireChanged(jdbc.sql("""
        UPDATE academic_periods SET academic_data_status='READY',version=:version,updated_at=:now
        WHERE academic_period_id=:period AND academic_data_status='IMPORTING' AND version=:expected
        """).param("version", newPeriodVersion).param("now", timestamp(now))
        .param("period", period.periodId).param("expected", expectedPeriod).update());

    int completedVersion = item.processVersion + 1;
    int progressVersion = item.processVersion + 2;
    requireChanged(jdbc.sql("""
        UPDATE student_import_processes
        SET status='COMPLETED',completed_at=:now,process_version=:version,
            last_progress_at=:now,last_progress_completed_items=(SELECT count(*) FROM student_import_plan_items WHERE import_id=:id AND item_type<>'FINALIZE_STUDENT_IMPORT'),
            last_progress_percent=100,updated_at=:now
        WHERE import_id=:id AND status='APPLYING' AND process_version=:expected
        """).param("version", progressVersion).param("now", timestamp(now))
        .param("id", importId).param("expected", item.processVersion).update());

    appendProcessEvent(importId, "StudentImportCompleted", completedVersion,
        completedPayload(importId, period.periodId, now), now);
    appendProcessEvent(importId, "StudentImportProgressUpdated", progressVersion,
        progressPayload(importId, period.periodId, "APPLYING", now), now);
    appendPeriodEvent(importId, period.periodId, newPeriodVersion,
        map("academicPeriodId", period.periodId,
            "previousAcademicDataStatus", "IMPORTING",
            "academicDataStatus", "READY",
            "reason", "IMPORT_COMPLETED",
            "changedAt", now), now);
  }

  private void fail(
      UUID importId, Item item, String code, String result, UUID resultMessageId, Instant now) {
    String resultStatus = result == null ? null : codec.node(result).path("status").asString(null);
    jdbc.sql("""
        UPDATE student_import_plan_items
        SET status='FAILED',received_result=COALESCE(CAST(:result AS jsonb),received_result),
            last_result_message_id=COALESCE(:resultMessage,last_result_message_id),
            last_result_status=COALESCE(:resultStatus,last_result_status),
            last_error_code=:code,last_error_at=:now,updated_at=:now
        WHERE import_id=:import AND plan_item_id=:item AND status<>'COMPLETED'
        """).param("result", result).param("resultMessage", resultMessageId).param("resultStatus", resultStatus)
        .param("code", code).param("now", timestamp(now)).param("import", importId).param("item", item.id).update();

    int failedVersion = item.processVersion + 1;
    int progressVersion = item.processVersion + 2;
    requireChanged(jdbc.sql("""
        UPDATE student_import_processes
        SET status='APPLICATION_FAILED',process_version=:version,failed_step=:step,failure_code=:code,
            failure_reason=:reason,recoverable=true,failed_at=:now,
            last_progress_at=:now,updated_at=:now
        WHERE import_id=:id AND status='APPLYING' AND process_version=:expected
        """).param("version", progressVersion).param("step", item.type).param("code", code)
        .param("reason", code).param("now", timestamp(now)).param("id", importId)
        .param("expected", item.processVersion).update());
    UUID periodId = periodId(importId);
    appendProcessEvent(importId, "StudentImportApplicationFailed", failedVersion,
        map("importId", importId, "academicPeriodId", periodId, "failedStep", item.type,
            "reason", code, "failedAt", now, "recoverable", true), now);
    appendProcessEvent(importId, "StudentImportProgressUpdated", progressVersion,
        progressPayload(importId, periodId, "APPLYING", now), now);
  }

  private boolean outstanding(UUID importId) {
    return jdbc.sql("""
        SELECT EXISTS (
          SELECT 1 FROM student_import_plan_items
          WHERE import_id=:id AND item_type<>'FINALIZE_STUDENT_IMPORT' AND status<>'COMPLETED')
        """).param("id", importId).query(Boolean.class).single();
  }

  private void maybeProgress(UUID importId, Instant now) {
    Progress progress = progress(importId);
    if (progress == null || progress.total == 0) return;
    int percent = (int) Math.floor((progress.completed * 100.0) / progress.total);
    boolean threshold = progress.completed - progress.lastCompleted >= 100
        || percent - progress.lastPercent >= 5;
    boolean throttleOpen = progress.lastAt == null || !progress.lastAt.plusSeconds(5).isAfter(now);
    if (!threshold || !throttleOpen) return;
    int version = progress.processVersion + 1;
    requireChanged(jdbc.sql("""
        UPDATE student_import_processes
        SET process_version=:version,last_progress_at=:now,
            last_progress_completed_items=:completed,last_progress_percent=:percent,updated_at=:now
        WHERE import_id=:id AND status='APPLYING' AND process_version=:expected
        """).param("version", version).param("now", timestamp(now)).param("completed", progress.completed)
        .param("percent", percent).param("id", importId).param("expected", progress.processVersion).update());
    appendProcessEvent(importId, "StudentImportProgressUpdated", version,
        progressPayload(importId, progress.periodId, "APPLYING", now), now);
  }

  private Progress progress(UUID importId) {
    return jdbc.sql("""
        SELECT p.academic_period_id,p.process_version,p.last_progress_at,
               p.last_progress_completed_items,p.last_progress_percent,
               count(*) FILTER (WHERE i.item_type<>'FINALIZE_STUDENT_IMPORT') AS total,
               count(*) FILTER (WHERE i.item_type<>'FINALIZE_STUDENT_IMPORT' AND i.status='COMPLETED') AS completed
        FROM student_import_processes p
        JOIN student_import_plan_items i ON i.import_id=p.import_id
        WHERE p.import_id=:id
        GROUP BY p.academic_period_id,p.process_version,p.last_progress_at,
                 p.last_progress_completed_items,p.last_progress_percent
        """).param("id", importId)
        .query((rs, n) -> new Progress(
            rs.getObject(1, UUID.class), rs.getInt(2),
            rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
            rs.getInt(4), rs.getInt(5), rs.getInt(6), rs.getInt(7)))
        .optional().orElse(null);
  }

  private Map<String, Object> progressPayload(UUID importId, UUID periodId, String phase, Instant now) {
    int total = jdbc.sql("SELECT count(*) FROM student_import_plan_items WHERE import_id=:id AND item_type<>'FINALIZE_STUDENT_IMPORT'")
        .param("id", importId).query(Integer.class).single();
    int completed = jdbc.sql("SELECT count(*) FROM student_import_plan_items WHERE import_id=:id AND item_type<>'FINALIZE_STUDENT_IMPORT' AND status='COMPLETED'")
        .param("id", importId).query(Integer.class).single();
    int failed = jdbc.sql("SELECT count(*) FROM student_import_plan_items WHERE import_id=:id AND item_type<>'FINALIZE_STUDENT_IMPORT' AND status='FAILED'")
        .param("id", importId).query(Integer.class).single();
    return map("importId", importId, "academicPeriodId", periodId, "phase", phase,
        "completedItems", completed, "totalItems", total, "failedItems", failed, "updatedAt", now);
  }

  private Map<String, Object> completedPayload(UUID importId, UUID periodId, Instant now) {
    Summary summary = summary(importId);
    return map("importId", importId, "academicPeriodId", periodId,
        "studentsCreated", summary.studentsCreated, "studentsUpdated", summary.studentsUpdated,
        "studentsReactivated", summary.studentsReactivated, "studentsDeactivated", summary.studentsDeactivated,
        "courseSectionsCreated", summary.courseSectionsCreated,
        "courseSectionsActivated", summary.courseSectionsActivated,
        "courseSectionsDeactivated", summary.courseSectionsDeactivated,
        "enrollmentsRegistered", summary.enrollmentsRegistered,
        "enrollmentsReactivated", summary.enrollmentsReactivated,
        "enrollmentsRemoved", summary.enrollmentsRemoved, "completedAt", now);
  }

  private Summary summary(UUID importId) {
    return jdbc.sql("""
        SELECT students_created,students_updated,students_reactivated,students_deactivated,
               course_sections_created,course_sections_activated,course_sections_deactivated,
               enrollments_registered,enrollments_reactivated,enrollments_removed
        FROM student_import_processes WHERE import_id=:id
        """).param("id", importId)
        .query((rs, n) -> new Summary(
            rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getInt(5),
            rs.getInt(6), rs.getInt(7), rs.getInt(8), rs.getInt(9), rs.getInt(10))).single();
  }

  private void appendProcessEvent(UUID importId, String type, int version, Map<String, Object> payload, Instant now) {
    outbox.append(UUID.randomUUID(), type, "EVENT", importId, version, importId, null,
        codec.json(payload), codec.json(map("actor", SYSTEM)), now);
  }

  private void appendPeriodEvent(UUID importId, UUID periodId, int version, Map<String, Object> payload, Instant now) {
    outbox.append(UUID.randomUUID(), "AcademicPeriodDataStatusChanged", "EVENT", periodId, version,
        importId, null, codec.json(payload), codec.json(map("actor", SYSTEM)), now);
  }

  private void appendDomainEvent(
      UUID importId, String type, UUID aggregateId, int aggregateVersion, Map<String, Object> payload, Instant now) {
    outbox.append(UUID.randomUUID(), type, "EVENT", aggregateId, aggregateVersion, importId, null,
        codec.json(payload), codec.json(map("actor", SYSTEM)), now);
  }

  private Profile profile(UUID id) {
    return jdbc.sql("""
        SELECT account_id,current_semester,academic_status::text,version
        FROM student_academic_profiles WHERE student_academic_profile_id=:id
        """).param("id", id)
        .query((rs, n) -> new Profile(
            rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3), rs.getInt(4)))
        .optional().orElseThrow(() -> new IllegalStateException("student profile not found"));
  }

  private Section section(UUID id) {
    return jdbc.sql("""
        SELECT academic_period_id,nrc,status::text,version FROM course_sections WHERE course_section_id=:id
        """).param("id", id)
        .query((rs, n) -> new Section(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4)))
        .optional().orElseThrow(() -> new IllegalStateException("course section not found"));
  }

  private Enrollment enrollment(UUID id) {
    return jdbc.sql("""
        SELECT student_academic_profile_id,course_section_id,status::text,version
        FROM student_course_sections WHERE enrollment_id=:id
        """).param("id", id)
        .query((rs, n) -> new Enrollment(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getString(3), rs.getInt(4)))
        .optional().orElseThrow(() -> new IllegalStateException("enrollment not found"));
  }

  private PeriodState periodState(UUID importId) {
    return jdbc.sql("""
        SELECT period.academic_period_id,period.version
        FROM academic_periods period
        JOIN student_import_processes p ON p.academic_period_id=period.academic_period_id
        WHERE p.import_id=:id
        """).param("id", importId)
        .query((rs, n) -> new PeriodState(rs.getObject(1, UUID.class), rs.getInt(2))).single();
  }

  private UUID periodId(UUID importId) {
    return jdbc.sql("SELECT academic_period_id FROM student_import_processes WHERE import_id=:id")
        .param("id", importId).query(UUID.class).single();
  }

  private int expectedVersion(Item item, String field) {
    JsonNode versions = codec.node(item.versions);
    if (!versions.has(field) || !versions.path(field).canConvertToInt()) {
      throw new IllegalStateException("missing planned expected version " + field);
    }
    return versions.path(field).asInt();
  }

  private static void requireChanged(int changed) {
    if (changed != 1) throw new IllegalStateException("student import state changed concurrently");
  }

  private static long retryDelay(int attempts) {
    return switch (attempts) {
      case 0 -> 120;
      case 1 -> 300;
      default -> 600;
    };
  }

  private static String destination(String type) {
    return switch (type) {
      case "CREATE_PERSON", "UPDATE_PERSON" -> "users";
      case "CREATE_ACCOUNT", "ACTIVATE_ACCOUNT", "DEACTIVATE_ACCOUNT" -> "authentication";
      case "ASSIGN_STUDENT_ROLE", "GRANT_STUDENT_SYSTEM_ACCESS" -> "authorization";
      default -> throw new IllegalArgumentException("local plan item has no remote destination");
    };
  }

  private static String command(String type) {
    return switch (type) {
      case "CREATE_PERSON" -> "CreatePerson";
      case "UPDATE_PERSON" -> "UpdatePerson";
      case "CREATE_ACCOUNT" -> "CreateAccount";
      case "ACTIVATE_ACCOUNT" -> "ActivateAccount";
      case "DEACTIVATE_ACCOUNT" -> "DeactivateAccount";
      case "ASSIGN_STUDENT_ROLE" -> "AssignAccountRole";
      case "GRANT_STUDENT_SYSTEM_ACCESS" -> "GrantSystemAccess";
      default -> throw new IllegalArgumentException("local plan item has no remote command");
    };
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static UUID uuid(JsonNode node, String field) {
    try {
      return node != null && node.hasNonNull(field) ? UUID.fromString(node.path(field).asString()) : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).asString();
  }

  private static Map<String, Object> map(Object... entries) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
    return result;
  }

  private record ApplicationProcess(UUID periodId, String periodDataStatus, int periodVersion,
                                    int processVersion, int executionGeneration) {}
  private record VersionCheck(String type, String effect, String versions) {}
  private record TargetVersion(String targetKey, UUID id, int expectedVersion, String table, String idColumn, String kind) {}
  private record PlanResult(String type, String effect, UUID resultMessageId, String resultStatus) {}
  private record Evidence(String resultMessageId) {}
  private record Profile(UUID accountId, int semester, String status, int version) {}
  private record Section(UUID periodId, String nrc, String status, int version) {}
  private record Enrollment(UUID profileId, UUID sectionId, String status, int version) {}
  private record Eligibility(UUID id, boolean eligible, int policyVersion, int version) {}
  private record PeriodState(UUID periodId, int version) {}
  private record Progress(UUID periodId, int processVersion, Instant lastAt,
                          int lastCompleted, int lastPercent, int total, int completed) {}
  private record Summary(int studentsCreated, int studentsUpdated, int studentsReactivated, int studentsDeactivated,
                         int courseSectionsCreated, int courseSectionsActivated, int courseSectionsDeactivated,
                         int enrollmentsRegistered, int enrollmentsReactivated, int enrollmentsRemoved) {}
  private record Item(UUID id, String type, String businessKey, String effect, String versions,
                      int generation, int attempts, UUID commandMessageId, UUID operationId,
                      String idempotencyKey, UUID lastResultMessageId, String lastResultStatus,
                      Instant nextAttemptAt, UUID authorizedBy, int processVersion, String status) {}
}
