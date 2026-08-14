package com.estoma.academic.application;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Durable process manager. Every state transition and outbound command shares one database transaction. */
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

  public StudentImportApplicationManager(JdbcClient jdbc, EnvelopeCodec codec, OutboxRepository outbox) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.outbox = outbox;
  }

  @Scheduled(fixedDelayString = "${estoma.student-import.application-delay:5s}")
  public void schedule() {
    jdbc.sql("SELECT import_id FROM student_import_processes WHERE status='APPLYING' ORDER BY application_started_at NULLS LAST")
        .query(UUID.class).list().forEach(this::runOne);
  }

  @Transactional
  public void runOne(UUID importId) {
    Item overdue = item(importId, "PROCESSING", true);
    if (overdue != null) {
      if (overdue.attempts >= 5 || firstPublishedOverThirtyMinutesAgo(importId, overdue.id)) {
        fail(importId, overdue, "RESULT_TIMEOUT", Instant.now());
      } else {
        jdbc.sql("""
            UPDATE student_import_plan_items SET status='PENDING',next_attempt_at=now(),updated_at=now()
            WHERE import_id=:import AND plan_item_id=:item AND status='PROCESSING'
            """).param("import", importId).param("item", overdue.id).update();
      }
      return;
    }

    Item next = item(importId, "PENDING", false);
    if (next == null || ("FINALIZE_STUDENT_IMPORT".equals(next.type) && outstanding(importId))) return;
    Instant now = Instant.now();
    if (LOCAL.contains(next.type)) {
      try {
        executeLocal(importId, next, now);
        complete(importId, next, null, now);
      } catch (RuntimeException ex) {
        fail(importId, next, "LOCAL_APPLICATION_FAILED", now);
      }
    } else {
      publishRemote(importId, next, now);
    }
  }

  private Item item(UUID importId, String status, boolean dueProcessing) {
    String due = dueProcessing ? "AND i.next_attempt_at <= now()" : "AND (i.next_attempt_at IS NULL OR i.next_attempt_at <= now())";
    return jdbc.sql("""
        SELECT i.plan_item_id,i.item_type::text,i.planned_effect::text,i.expected_versions::text,
          i.execution_generation,i.attempts,i.current_command_message_id,i.current_idempotency_key,
          p.authorized_by_account_id,p.process_version
        FROM student_import_plan_items i JOIN student_import_processes p ON p.import_id=i.import_id
        WHERE i.import_id=:id AND p.status='APPLYING'
          AND i.status=CAST(:status AS student_import_plan_item_status) %s
        ORDER BY CASE WHEN i.item_type IN ('REGISTER_COURSE_SECTION','ACTIVATE_COURSE_SECTION','DEACTIVATE_COURSE_SECTION') THEN 1
          WHEN i.item_type IN ('CREATE_PERSON','UPDATE_PERSON') THEN 2
          WHEN i.item_type IN ('CREATE_ACCOUNT','ACTIVATE_ACCOUNT','DEACTIVATE_ACCOUNT') THEN 3
          WHEN i.item_type IN ('ASSIGN_STUDENT_ROLE','GRANT_STUDENT_SYSTEM_ACCESS') THEN 4
          WHEN i.item_type='FINALIZE_STUDENT_IMPORT' THEN 9 ELSE 5 END, i.created_at
        FOR UPDATE SKIP LOCKED LIMIT 1
        """.formatted(due)).param("id", importId).param("status", status)
        .query((rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getInt(5), rs.getInt(6), rs.getObject(7, UUID.class), rs.getString(8),
            rs.getObject(9, UUID.class), rs.getInt(10))).optional().orElse(null);
  }

  private boolean firstPublishedOverThirtyMinutesAgo(UUID importId, UUID itemId) {
    return jdbc.sql("SELECT first_published_at <= now()-interval '30 minutes' FROM student_import_plan_items WHERE import_id=:import AND plan_item_id=:item")
        .param("import", importId).param("item", itemId).query(Boolean.class).single();
  }

  private void publishRemote(UUID importId, Item item, Instant now) {
    UUID messageId = UUID.randomUUID();
    String idempotencyKey = item.idempotencyKey == null
        ? "student-import:" + importId + ":" + item.id + ":" + item.generation
        : item.idempotencyKey;
    JsonNode effect = codec.node(item.effect);
    Map<String, Object> payload = new LinkedHashMap<>();
    effect.properties().forEach(entry -> payload.put(entry.getKey(), entry.getValue()));
    payload.put("importExecution", Map.of(
        "importId", importId, "planItemId", item.id, "executionGeneration", item.generation,
        "authorizedByAccountId", item.authorizedBy, "scope", item.type,
        "causalDependencies", List.of(), "authoritativeEvidence", List.of()));
    outbox.append(messageId, command(item.type), "COMMAND", importId, item.processVersion, importId, null,
        codec.json(payload), codec.json(Map.of("destination", destination(item.type), "operationId", UUID.randomUUID(),
            "idempotencyKey", idempotencyKey, "actor", SYSTEM)), now);
    int changed = jdbc.sql("""
        UPDATE student_import_plan_items
        SET status='PROCESSING',current_command_message_id=:message,current_idempotency_key=:key,
            attempts=attempts+1,first_published_at=COALESCE(first_published_at,:now),next_attempt_at=:next,updated_at=:now
        WHERE import_id=:import AND plan_item_id=:item AND status='PENDING' AND execution_generation=:generation
        """).param("message", messageId).param("key", idempotencyKey).param("now", timestamp(now))
        .param("next", timestamp(now.plusSeconds(retryDelay(item.attempts))))
        .param("import", importId).param("item", item.id).param("generation", item.generation).update();
    if (changed != 1) throw new IllegalStateException("student import plan item changed while publishing");
  }

  @Transactional
  public boolean applyResult(MessageEnvelope result) {
    if (!"RESULT".equals(result.messageKind()) || result.messageId() == null) throw new IllegalArgumentException("invalid result");
    if (jdbc.sql("INSERT INTO processed_messages (consumer_name,message_id,correlation_id,message_type,message_kind,producer,aggregate_id,aggregate_version,envelope,processing_state,processed_at) VALUES ('academic.import-results',:id,:correlation,:type,:kind,:producer,:aggregate,:version,CAST(:envelope AS jsonb),'APPLIED',now()) ON CONFLICT DO NOTHING")
        .param("id", result.messageId()).param("correlation", result.correlationId()).param("type", result.messageType())
        .param("kind", result.messageKind()).param("producer", result.producer()).param("aggregate", result.aggregateId())
        .param("version", result.aggregateVersion()).param("envelope", codec.json(result)).update() == 0) return false;

    JsonNode data = result.payload().path("data");
    UUID importId = uuid(data, "importId");
    UUID itemId = uuid(data, "planItemId");
    if (importId == null || itemId == null) return false;
    Item item = resultItem(importId, itemId);
    if (item == null || !"PROCESSING".equals(item.status) || !Objects.equals(result.correlationId(), importId)
        || !Objects.equals(result.causationId(), item.commandMessageId) || item.generation != data.path("executionGeneration").asInt(-1)
        || !result.messageType().equals(command(item.type) + "Result") || !destination(item.type).equals(result.producer())) return false;

    Instant now = Instant.now();
    String outcome = result.payload().path("status").asString();
    if ("SUCCEEDED".equals(outcome)) complete(importId, item, codec.json(result.payload()), now);
    else fail(importId, item, result.payload().path("error").path("code").asString("REMOTE_" + outcome),
        codec.json(result.payload()), now);
    return true;
  }

  private Item resultItem(UUID importId, UUID itemId) {
    return jdbc.sql("""
        SELECT i.plan_item_id,i.item_type::text,i.planned_effect::text,i.expected_versions::text,
          i.execution_generation,i.attempts,i.current_command_message_id,i.current_idempotency_key,
          p.authorized_by_account_id,p.process_version,i.status::text
        FROM student_import_plan_items i JOIN student_import_processes p ON p.import_id=i.import_id
        WHERE i.import_id=:import AND i.plan_item_id=:item FOR UPDATE
        """).param("import", importId).param("item", itemId)
        .query((rs, n) -> new Item(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getInt(5), rs.getInt(6), rs.getObject(7, UUID.class), rs.getString(8), rs.getObject(9, UUID.class),
            rs.getInt(10), rs.getString(11))).optional().orElse(null);
  }

  private void executeLocal(UUID importId, Item item, Instant now) {
    JsonNode effect = codec.node(item.effect);
    int changed;
    switch (item.type) {
      case "REGISTER_COURSE_SECTION" -> {
        changed = jdbc.sql("INSERT INTO course_sections (course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at) VALUES (:id,:period,:nrc,:name,'ACTIVE',1,:now,:now)")
          .param("id", uuid(effect, "courseSectionId")).param("period", uuid(effect, "academicPeriodId"))
          .param("nrc", text(effect, "nrc")).param("name", text(effect, "name")).param("now", timestamp(now)).update();
        requireChanged(changed);
      }
      case "CREATE_STUDENT_PROFILE" -> {
        changed = jdbc.sql("INSERT INTO student_academic_profiles (student_academic_profile_id,person_id,account_id,student_number,student_number_normalized,current_semester,academic_status,version,student_number_version,created_at,updated_at) VALUES (:id,:person,:account,:number,:normalized,:semester,'ACTIVE',1,0,:now,:now)")
          .param("id", uuid(effect, "studentAcademicProfileId")).param("person", uuid(effect, "personId"))
          .param("account", uuid(effect, "accountId")).param("number", text(effect, "studentNumber"))
          .param("normalized", StudentAcademicProfile.normalizedStudentNumber(text(effect, "studentNumber")))
          .param("semester", effect.path("currentSemester").asInt()).param("now", timestamp(now)).update();
        requireChanged(changed);
      }
      case "CHANGE_STUDENT_SEMESTER" -> {
        changed = jdbc.sql("UPDATE student_academic_profiles SET current_semester=:semester,version=version+1,updated_at=:now WHERE student_academic_profile_id=:id AND version=:expected")
            .param("id", uuid(effect, "studentAcademicProfileId")).param("semester", effect.path("currentSemester").asInt())
            .param("expected", expectedVersion(item, "studentAcademicProfileVersion")).param("now", timestamp(now)).update();
        requireChanged(changed);
      }
      case "ACTIVATE_STUDENT_PROFILE", "DEACTIVATE_STUDENT_PROFILE" -> {
        changed = jdbc.sql("UPDATE student_academic_profiles SET academic_status=CAST(:status AS academic_profile_status),version=version+1,updated_at=:now WHERE student_academic_profile_id=:id AND version=:expected")
            .param("id", uuid(effect, "studentAcademicProfileId")).param("status", item.type.startsWith("ACTIVATE") ? "ACTIVE" : "INACTIVE")
            .param("expected", expectedVersion(item, "studentAcademicProfileVersion")).param("now", timestamp(now)).update();
        requireChanged(changed);
      }
      case "ADD_STUDENT_ENROLLMENT" -> {
        changed = jdbc.sql("INSERT INTO student_course_sections (enrollment_id,student_academic_profile_id,course_section_id,status,enrolled_at,version,updated_at) VALUES (:id,:student,:section,'ACTIVE',:now,1,:now)")
          .param("id", uuid(effect, "enrollmentId")).param("student", uuid(effect, "studentAcademicProfileId"))
          .param("section", uuid(effect, "courseSectionId")).param("now", timestamp(now)).update();
        requireChanged(changed);
      }
      case "REACTIVATE_STUDENT_ENROLLMENT", "REMOVE_STUDENT_ENROLLMENT" -> {
        changed = jdbc.sql("UPDATE student_course_sections SET status=CAST(:status AS enrollment_status),version=version+1,updated_at=:now WHERE student_academic_profile_id=:student AND course_section_id=:section AND version=:expected")
            .param("student", uuid(effect, "studentAcademicProfileId")).param("section", uuid(effect, "courseSectionId"))
            .param("status", item.type.startsWith("REACTIVATE") ? "ACTIVE" : "REMOVED")
            .param("expected", expectedVersion(item, "enrollmentVersion")).param("now", timestamp(now)).update();
        requireChanged(changed);
      }
      case "ACTIVATE_COURSE_SECTION", "DEACTIVATE_COURSE_SECTION" -> {
        changed = jdbc.sql("UPDATE course_sections SET status=CAST(:status AS course_section_status),version=version+1,updated_at=:now WHERE course_section_id=:id AND version=:expected")
            .param("id", uuid(effect, "courseSectionId")).param("status", item.type.startsWith("ACTIVATE") ? "ACTIVE" : "INACTIVE")
            .param("expected", expectedVersion(item, "courseSectionVersion")).param("now", timestamp(now)).update();
        requireChanged(changed);
      }
      case "FINALIZE_STUDENT_IMPORT" -> { }
      default -> throw new IllegalArgumentException("unsupported local item");
    }
  }

  private void complete(UUID importId, Item item, String result, Instant now) {
    if ("FINALIZE_STUDENT_IMPORT".equals(item.type)) finalizeImport(importId, item, now);
    int changed = jdbc.sql("UPDATE student_import_plan_items SET status='COMPLETED',received_result=COALESCE(CAST(:result AS jsonb),received_result),updated_at=:now WHERE import_id=:import AND plan_item_id=:item AND status IN ('PENDING','PROCESSING')")
        .param("result", result).param("now", timestamp(now)).param("import", importId).param("item", item.id).update();
    requireChanged(changed);
    if (!"FINALIZE_STUDENT_IMPORT".equals(item.type)) progress(importId, now);
  }

  private void finalizeImport(UUID importId, Item item, Instant now) {
    if (outstanding(importId)) throw new IllegalStateException("student import still has outstanding plan items");
    int periodChanged = jdbc.sql("UPDATE academic_periods SET academic_data_status='READY',version=version+1,updated_at=:now WHERE academic_period_id=(SELECT academic_period_id FROM student_import_processes WHERE import_id=:id) AND version=:expected")
        .param("id", importId).param("expected", expectedVersion(item, "academicPeriodVersion")).param("now", timestamp(now)).update();
    requireChanged(periodChanged);
    int processChanged = jdbc.sql("UPDATE student_import_processes SET status='COMPLETED',completed_at=:now,process_version=process_version+1,updated_at=:now WHERE import_id=:id AND status='APPLYING' AND process_version=:expected")
        .param("id", importId).param("expected", item.processVersion).param("now", timestamp(now)).update();
    requireChanged(processChanged);
    event(importId, "StudentImportCompleted", now);
  }

  private void fail(UUID importId, Item item, String code, Instant now) {
    fail(importId, item, code, null, now);
  }

  private void fail(UUID importId, Item item, String code, String result, Instant now) {
    jdbc.sql("UPDATE student_import_plan_items SET status='FAILED',received_result=COALESCE(CAST(:result AS jsonb),received_result),last_error_code=:code,last_error_at=:now,updated_at=:now WHERE import_id=:import AND plan_item_id=:item AND status<>'COMPLETED'")
        .param("result", result).param("code", code).param("now", timestamp(now)).param("import", importId).param("item", item.id).update();
    int changed = jdbc.sql("UPDATE student_import_processes SET status='APPLICATION_FAILED',process_version=process_version+1,failed_step=:step,failure_code=:code,recoverable=true,failed_at=:now,updated_at=:now WHERE import_id=:id AND status='APPLYING' AND process_version=:expected")
        .param("step", item.type).param("code", code).param("now", timestamp(now)).param("id", importId)
        .param("expected", item.processVersion).update();
    requireChanged(changed);
    event(importId, "StudentImportApplicationFailed", now);
  }

  private boolean outstanding(UUID importId) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM student_import_plan_items WHERE import_id=:id AND item_type<>'FINALIZE_STUDENT_IMPORT' AND status<>'COMPLETED')")
        .param("id", importId).query(Boolean.class).single();
  }

  private void progress(UUID importId, Instant now) {
    long done = jdbc.sql("SELECT count(*) FROM student_import_plan_items WHERE import_id=:id AND status='COMPLETED'")
        .param("id", importId).query(Long.class).single();
    if (done % 100 == 0) event(importId, "StudentImportProgressUpdated", now);
  }

  private void event(UUID importId, String type, Instant now) {
    outbox.append(UUID.randomUUID(), type, "EVENT", importId, processVersion(importId), importId, null,
        codec.json(Map.of("importId", importId, "occurredAt", now)), codec.json(Map.of("actor", SYSTEM)), now);
  }

  private int processVersion(UUID importId) {
    return jdbc.sql("SELECT process_version FROM student_import_processes WHERE import_id=:id")
        .param("id", importId).query(Integer.class).single();
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
      case "ASSIGN_STUDENT_ROLE" -> "AssignStudentRole";
      case "GRANT_STUDENT_SYSTEM_ACCESS" -> "GrantStudentSystemAccess";
      default -> throw new IllegalArgumentException("local plan item has no remote command");
    };
  }

  private static Timestamp timestamp(Instant instant) {
    return Timestamp.from(instant);
  }

  private static UUID uuid(JsonNode node, String field) {
    try {
      return node.hasNonNull(field) ? UUID.fromString(node.path(field).asString()) : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private static String text(JsonNode node, String field) {
    return node.path(field).asString();
  }

  private record Item(UUID id, String type, String effect, String versions, int generation, int attempts,
                      UUID commandMessageId, String idempotencyKey, UUID authorizedBy, int processVersion,
                      String status) {
    private Item(UUID id, String type, String effect, String versions, int generation, int attempts,
                 UUID commandMessageId, String idempotencyKey, UUID authorizedBy, int processVersion) {
      this(id, type, effect, versions, generation, attempts, commandMessageId, idempotencyKey, authorizedBy,
          processVersion, null);
    }
  }
}
