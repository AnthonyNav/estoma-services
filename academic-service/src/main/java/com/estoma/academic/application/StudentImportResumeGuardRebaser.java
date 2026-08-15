package com.estoma.academic.application;

import com.estoma.academic.messaging.EnvelopeCodec;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Reconciles only technical version guards whose business effect is still provably compatible. */
final class StudentImportResumeGuardRebaser {
  private StudentImportResumeGuardRebaser() {}

  static void reconcile(
      JdbcClient jdbc,
      EnvelopeCodec codec,
      UUID importId,
      int executionGeneration,
      UUID resolvedByAccountId,
      Instant now) {
    List<GuardItem> items = jdbc.sql("""
        SELECT plan_item_id,item_type::text,business_key::text,planned_effect::text,
               expected_versions::text,generated_entity_id,status::text
        FROM student_import_plan_items
        WHERE import_id=:id AND status IN ('PENDING','FAILED')
        ORDER BY created_at,plan_item_id
        """).param("id", importId)
        .query((rs, n) -> new GuardItem(
            rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
            rs.getString(5), rs.getObject(6, UUID.class), rs.getString(7)))
        .list();

    Map<String, List<GuardedItem>> groups = new LinkedHashMap<>();
    for (GuardItem item : items) {
      GuardKey key = guardKey(codec.node(item.effect), codec.node(item.expectedVersions), item.type);
      if (key == null) continue;
      groups.computeIfAbsent(key.targetKey, ignored -> new ArrayList<>())
          .add(new GuardedItem(item, key));
    }

    for (List<GuardedItem> group : groups.values()) {
      group.sort(Comparator.comparingInt(value -> value.key.expectedVersion));
      GuardedItem anchor = group.getFirst();
      Current current = current(jdbc, codec, importId, anchor);
      if (current == null) throw new AcademicRejectedException("IMPORT_RESUME_NOT_ALLOWED");
      int initialExpected = anchor.key.expectedVersion;
      if (current.version < initialExpected) {
        throw new AcademicRejectedException("IMPORT_RESUME_NOT_ALLOWED");
      }
      if (current.version == initialExpected) continue;
      if (!compatible(jdbc, codec, importId, anchor, current)) {
        throw new AcademicRejectedException("IMPORT_RESUME_NOT_ALLOWED");
      }

      int delta = current.version - initialExpected;
      for (GuardedItem guarded : group) {
        int rebasedVersion = guarded.key.expectedVersion + delta;
        String previous = guarded.item.expectedVersions;
        JsonNode parsed = codec.node(previous);
        if (!(parsed instanceof ObjectNode expected)) {
          throw new AcademicRejectedException("IMPORT_RESUME_NOT_ALLOWED");
        }
        expected.put(guarded.key.versionField, rebasedVersion);
        String replacement = codec.json(expected);
        if (previous.equals(replacement)) continue;

        int changed = jdbc.sql("""
            UPDATE student_import_plan_items
            SET expected_versions=CAST(:versions AS jsonb),updated_at=:now
            WHERE import_id=:import AND plan_item_id=:item AND status IN ('PENDING','FAILED')
              AND expected_versions=CAST(:previous AS jsonb)
            """).param("versions", replacement).param("now", Timestamp.from(now))
            .param("import", importId).param("item", guarded.item.id)
            .param("previous", previous).update();
        if (changed != 1) throw new AcademicRejectedException("IMPORT_RESUME_NOT_ALLOWED");

        jdbc.sql("""
            INSERT INTO student_import_plan_item_rebases (
              rebase_id,import_id,plan_item_id,execution_generation,
              previous_expected_versions,new_expected_versions,reason,resolved_by_account_id,resolved_at)
            VALUES (:rebase,:import,:item,:generation,CAST(:previous AS jsonb),CAST(:current AS jsonb),
              'RESUME_GUARD_RECONCILIATION',:actor,:now)
            """).param("rebase", UUID.randomUUID()).param("import", importId)
            .param("item", guarded.item.id).param("generation", executionGeneration)
            .param("previous", previous).param("current", replacement)
            .param("actor", resolvedByAccountId).param("now", Timestamp.from(now)).update();
      }
    }
  }

  private static GuardKey guardKey(JsonNode effect, JsonNode versions, String type) {
    return switch (type) {
      case "UPDATE_PERSON" -> key("person", effect, "personId", versions, "personVersion");
      case "ACTIVATE_ACCOUNT", "DEACTIVATE_ACCOUNT" ->
          key("account", effect, "accountId", versions, "accountVersion");
      case "ASSIGN_STUDENT_ROLE" -> key("role", effect, "accountId", versions, "roleVersion");
      case "ACTIVATE_STUDENT_PROFILE", "DEACTIVATE_STUDENT_PROFILE", "CHANGE_STUDENT_SEMESTER" ->
          key("profile", effect, "studentAcademicProfileId", versions, "studentAcademicProfileVersion");
      case "ACTIVATE_COURSE_SECTION", "DEACTIVATE_COURSE_SECTION" ->
          key("course", effect, "courseSectionId", versions, "courseSectionVersion");
      case "REACTIVATE_STUDENT_ENROLLMENT", "REMOVE_STUDENT_ENROLLMENT" ->
          key("enrollment", effect, "enrollmentId", versions, "enrollmentVersion");
      case "FINALIZE_STUDENT_IMPORT" ->
          key("period", effect, "academicPeriodId", versions, "academicPeriodVersion");
      default -> null;
    };
  }

  private static GuardKey key(
      String kind, JsonNode effect, String idField, JsonNode versions, String versionField) {
    UUID id = uuid(effect, idField);
    if (id == null || !versions.has(versionField) || !versions.path(versionField).canConvertToInt()) return null;
    return new GuardKey(kind + ":" + id, kind, id, versionField, versions.path(versionField).asInt());
  }

  private static Current current(JdbcClient jdbc, EnvelopeCodec codec, UUID importId, GuardedItem guarded) {
    UUID id = guarded.key.id;
    return switch (guarded.key.kind) {
      case "person" -> jdbc.sql("SELECT source_version,status FROM person_status_projection WHERE person_id=:id")
          .param("id", id).query((rs, n) -> new Current(rs.getInt(1), rs.getString(2), null, null, null, null, null))
          .optional().orElse(null);
      case "account" -> jdbc.sql("""
          SELECT source_version,status,login_type,last_deactivation_reason_code,person_id
          FROM account_status_projection WHERE account_id=:id
          """).param("id", id)
          .query((rs, n) -> new Current(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
              rs.getObject(5, UUID.class), null, null)).optional().orElse(null);
      case "role" -> jdbc.sql("""
          SELECT source_version,status FROM authorization_context_projection
          WHERE account_id=:id AND role_code='ALUMNO'
          """).param("id", id)
          .query((rs, n) -> new Current(rs.getInt(1), rs.getString(2), null, null, null, null, null))
          .optional().orElse(null);
      case "profile" -> jdbc.sql("""
          SELECT version,academic_status::text,current_semester FROM student_academic_profiles
          WHERE student_academic_profile_id=:id
          """).param("id", id)
          .query((rs, n) -> new Current(rs.getInt(1), rs.getString(2), null, null, null, rs.getInt(3), null))
          .optional().orElse(null);
      case "course" -> jdbc.sql("""
          SELECT version,status::text,nrc,name,academic_period_id FROM course_sections
          WHERE course_section_id=:id
          """).param("id", id)
          .query((rs, n) -> new Current(rs.getInt(1), rs.getString(2), null, null,
              rs.getObject(5, UUID.class), null, rs.getString(3) + "\u0000" + rs.getString(4)))
          .optional().orElse(null);
      case "enrollment" -> jdbc.sql("""
          SELECT version,status::text,student_academic_profile_id,course_section_id
          FROM student_course_sections WHERE enrollment_id=:id
          """).param("id", id)
          .query((rs, n) -> new Current(rs.getInt(1), rs.getString(2), null, null,
              rs.getObject(3, UUID.class), null, rs.getObject(4, UUID.class).toString()))
          .optional().orElse(null);
      case "period" -> jdbc.sql("""
          SELECT version,academic_data_status::text FROM academic_periods WHERE academic_period_id=:id
          """).param("id", id)
          .query((rs, n) -> new Current(rs.getInt(1), rs.getString(2), null, null, null, null, null))
          .optional().orElse(null);
      default -> null;
    };
  }

  private static boolean compatible(
      JdbcClient jdbc, EnvelopeCodec codec, UUID importId, GuardedItem guarded, Current current) {
    JsonNode effect = codec.node(guarded.item.effect);
    return switch (guarded.item.type) {
      case "ACTIVATE_ACCOUNT" -> "INACTIVE".equals(current.status)
          && "MATRICULA".equals(current.loginType)
          && "ACADEMIC_ABSENCE".equals(current.reasonCode);
      case "DEACTIVATE_ACCOUNT" -> "ACTIVE".equals(current.status)
          && "MATRICULA".equals(current.loginType)
          && matchesUuid(effect, "personId", current.relatedId);
      case "ACTIVATE_COURSE_SECTION" -> "INACTIVE".equals(current.status)
          && courseMatches(effect, current, true);
      case "DEACTIVATE_COURSE_SECTION" -> "ACTIVE".equals(current.status)
          && courseMatchesDesiredSnapshot(jdbc, importId, effect, current);
      case "REACTIVATE_STUDENT_ENROLLMENT" -> "REMOVED".equals(current.status)
          && enrollmentMatches(effect, current);
      case "REMOVE_STUDENT_ENROLLMENT" -> "ACTIVE".equals(current.status)
          && enrollmentMatches(effect, current);
      default -> false;
    };
  }

  private static boolean courseMatches(JsonNode effect, Current current, boolean requireName) {
    if (!matchesUuid(effect, "academicPeriodId", current.relatedId)) return false;
    String[] values = current.extra == null ? new String[] {"", ""} : current.extra.split("\u0000", -1);
    if (values.length != 2 || !effect.path("nrc").asString().equals(values[0])) return false;
    return !requireName || effect.path("name").asString().equals(values[1]);
  }

  private static boolean courseMatchesDesiredSnapshot(
      JdbcClient jdbc, UUID importId, JsonNode effect, Current current) {
    if (!courseMatches(effect, current, false)) return false;
    String nrc = effect.path("nrc").asString();
    String currentName = current.extra == null ? null : current.extra.split("\u0000", -1)[1];
    String desiredName = jdbc.sql("""
        SELECT row_data->>'name' FROM student_import_validation_rows
        WHERE import_id=:import AND row_type='NRC' AND row_data->>'nrc'=:nrc
        """).param("import", importId).param("nrc", nrc)
        .query(String.class).optional().orElse(null);
    return desiredName != null && desiredName.equals(currentName);
  }

  private static boolean enrollmentMatches(JsonNode effect, Current current) {
    return matchesUuid(effect, "studentAcademicProfileId", current.relatedId)
        && current.extra != null
        && effect.path("courseSectionId").asString().equals(current.extra);
  }

  private static boolean matchesUuid(JsonNode effect, String field, UUID value) {
    UUID expected = uuid(effect, field);
    return expected != null && expected.equals(value);
  }

  private static UUID uuid(JsonNode node, String field) {
    try {
      return node != null && node.hasNonNull(field) ? UUID.fromString(node.path(field).asString()) : null;
    } catch (IllegalArgumentException ex) {
      return null;
    }
  }

  private record GuardItem(
      UUID id,
      String type,
      String businessKey,
      String effect,
      String expectedVersions,
      UUID generatedEntityId,
      String status) {}
  private record GuardKey(String targetKey, String kind, UUID id, String versionField, int expectedVersion) {}
  private record GuardedItem(GuardItem item, GuardKey key) {}
  private record Current(
      int version,
      String status,
      String loginType,
      String reasonCode,
      UUID relatedId,
      Integer semester,
      String extra) {}
}
