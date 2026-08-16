package com.estoma.academic.application;

import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.messaging.EnvelopeCodec;
import com.estoma.academic.persistence.OutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** Reads persisted eligibility snapshots and emits the correlated durable Result. */
@Component
public class AcademicQueryDispatcher {
  private static final String SYSTEM_CODE = "PRACTICAS_PROFESIONALES";

  private final JdbcClient jdbc;
  private final OutboxRepository outbox;
  private final EnvelopeCodec codec;

  public AcademicQueryDispatcher(JdbcClient jdbc, OutboxRepository outbox, EnvelopeCodec codec) {
    this.jdbc = jdbc;
    this.outbox = outbox;
    this.codec = codec;
  }

  public void dispatch(MessageEnvelope query) {
    if ("GetStudentAcademicEligibilitySnapshot".equals(query.messageType())) {
      get(query);
    } else if ("ListStudentAcademicEligibilitySnapshotsPage".equals(query.messageType())) {
      list(query);
    } else {
      throw new IllegalArgumentException("unsupported Academic Query");
    }
  }

  private void get(MessageEnvelope query) {
    JsonNode payload = query.payload();
    UUID profileId = uuid(payload, "studentAcademicProfileId");
    UUID accountId = uuid(payload, "accountId");
    Eligibility eligibility = profileId != null
        ? findByProfile(profileId)
        : findByAccount(accountId);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", eligibility == null ? "NOT_FOUND" : "FOUND");
    result.put("data", eligibility == null ? null : eligibility.data());
    appendResult(query, "GetStudentAcademicEligibilitySnapshotResult", eligibility, result);
  }

  private void list(MessageEnvelope query) {
    JsonNode payload = query.payload();
    UUID cursor = uuid(payload, "cursor");
    int limit = payload.path("limit").asInt();
    List<Eligibility> rows = jdbc.sql("""
        SELECT e.eligibility_id,e.student_academic_profile_id,p.account_id,e.system_code,
               e.eligible,e.policy_version,e.eligibility_version,e.academic_profile_version,
               e.evaluated_at,e.updated_at
        FROM student_academic_eligibilities e
        JOIN student_academic_profiles p ON p.student_academic_profile_id=e.student_academic_profile_id
        WHERE e.system_code=:system
          AND (:cursor IS NULL OR e.eligibility_id > :cursor)
        ORDER BY e.eligibility_id
        LIMIT :limit
        """)
        .param("system", SYSTEM_CODE)
        .param("cursor", cursor)
        .param("limit", limit + 1)
        .query((rs, n) -> new Eligibility(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
            rs.getString(4), rs.getBoolean(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
            rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant()))
        .list();

    boolean hasMore = rows.size() > limit;
    if (hasMore) {
      rows = new ArrayList<>(rows.subList(0, limit));
    }
    String nextCursor = hasMore && !rows.isEmpty()
        ? rows.get(rows.size() - 1).eligibilityId().toString()
        : null;
    List<Map<String, Object>> items = rows.stream().map(Eligibility::data).toList();
    Map<String, Object> data = new LinkedHashMap<>();
    data.put("items", items);
    data.put("nextCursor", nextCursor);
    data.put("hasMore", hasMore);
    appendResult(query, "ListStudentAcademicEligibilitySnapshotsPageResult", null,
        Map.of("status", "SUCCEEDED", "data", data));
  }

  private Eligibility findByProfile(UUID profileId) {
    return jdbc.sql("""
        SELECT e.eligibility_id,e.student_academic_profile_id,p.account_id,e.system_code,
               e.eligible,e.policy_version,e.eligibility_version,e.academic_profile_version,
               e.evaluated_at,e.updated_at
        FROM student_academic_eligibilities e
        JOIN student_academic_profiles p ON p.student_academic_profile_id=e.student_academic_profile_id
        WHERE e.student_academic_profile_id=:profile AND e.system_code=:system
        """)
        .param("profile", profileId).param("system", SYSTEM_CODE)
        .query((rs, n) -> new Eligibility(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
            rs.getString(4), rs.getBoolean(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
            rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant()))
        .optional().orElse(null);
  }

  private Eligibility findByAccount(UUID accountId) {
    return jdbc.sql("""
        SELECT e.eligibility_id,e.student_academic_profile_id,p.account_id,e.system_code,
               e.eligible,e.policy_version,e.eligibility_version,e.academic_profile_version,
               e.evaluated_at,e.updated_at
        FROM student_academic_eligibilities e
        JOIN student_academic_profiles p ON p.student_academic_profile_id=e.student_academic_profile_id
        WHERE p.account_id=:account AND e.system_code=:system
        """)
        .param("account", accountId).param("system", SYSTEM_CODE)
        .query((rs, n) -> new Eligibility(
            rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
            rs.getString(4), rs.getBoolean(5), rs.getInt(6), rs.getInt(7), rs.getInt(8),
            rs.getTimestamp(9).toInstant(), rs.getTimestamp(10).toInstant()))
        .optional().orElse(null);
  }

  private void appendResult(MessageEnvelope query, String messageType, Eligibility eligibility,
      Map<String, Object> payload) {
    UUID aggregateId = eligibility == null ? null : eligibility.studentAcademicProfileId();
    Integer aggregateVersion = eligibility == null ? null : eligibility.eligibilityVersion();
    outbox.append(
        UUID.randomUUID(),
        messageType,
        "RESULT",
        aggregateId,
        aggregateVersion,
        query.correlationId(),
        query.messageId(),
        codec.json(payload),
        codec.json(Map.of(
            "destination", query.producer(),
            "operationId", query.operationId(),
            "actor", query.actor())),
        Instant.now());
  }

  private static UUID uuid(JsonNode payload, String field) {
    if (!payload.hasNonNull(field)) {
      return null;
    }
    try {
      return UUID.fromString(payload.path(field).asString());
    } catch (IllegalArgumentException ex) {
      throw new IllegalArgumentException("invalid " + field, ex);
    }
  }

  private record Eligibility(
      UUID eligibilityId,
      UUID studentAcademicProfileId,
      UUID accountId,
      String systemCode,
      boolean eligible,
      int policyVersion,
      int eligibilityVersion,
      int academicProfileVersion,
      Instant evaluatedAt,
      Instant updatedAt) {
    Map<String, Object> data() {
      Map<String, Object> data = new LinkedHashMap<>();
      data.put("eligibilityId", eligibilityId);
      data.put("studentAcademicProfileId", studentAcademicProfileId);
      data.put("accountId", accountId);
      data.put("systemCode", systemCode);
      data.put("eligible", eligible);
      data.put("policyVersion", policyVersion);
      data.put("eligibilityVersion", eligibilityVersion);
      data.put("academicProfileVersion", academicProfileVersion);
      data.put("evaluatedAt", evaluatedAt);
      data.put("updatedAt", updatedAt);
      return data;
    }
  }
}
