package com.estoma.academic.application;

import com.estoma.academic.contract.MessageEnvelope;
import com.estoma.academic.domain.AcademicEligibilityPolicyV1;
import com.estoma.academic.domain.StudentAcademicProfile;
import com.estoma.academic.messaging.EnvelopeCodec;
import com.estoma.academic.persistence.OutboxRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import tools.jackson.databind.JsonNode;

/** The single transaction boundary for commands: inbox, idempotency, domain state and outbox commit together. */
@Component
public class AcademicCommandDispatcher {
  private final JdbcClient jdbc;
  private final OutboxRepository outbox;
  private final EnvelopeCodec codec;
  private final AcademicEligibilityPolicyRegistry eligibilityPolicies;

  public AcademicCommandDispatcher(JdbcClient jdbc, OutboxRepository outbox, EnvelopeCodec codec, AcademicEligibilityPolicyRegistry eligibilityPolicies) {
    this.jdbc = jdbc; this.outbox = outbox; this.codec = codec; this.eligibilityPolicies = eligibilityPolicies;
  }

  @Transactional
  public Outcome dispatch(MessageEnvelope e) {
    Instant now = Instant.now();
    if (jdbc.sql("""
        INSERT INTO processed_messages (consumer_name,message_id,correlation_id,message_type,message_kind,producer,aggregate_id,aggregate_version,envelope,processing_state,reconciliation_state,processed_at)
        VALUES ('academic.commands',:id,:correlation,:type,:kind,:producer,:aggregate,:version,CAST(:envelope AS jsonb),'APPLIED','EVENT_PENDING',:now)
        ON CONFLICT DO NOTHING
        """).param("id", e.messageId()).param("correlation", e.correlationId()).param("type",e.messageType()).param("kind",e.messageKind()).param("producer",e.producer()).param("aggregate",e.aggregateId()).param("version",e.aggregateVersion()).param("envelope",codec.json(e)).param("now", java.sql.Timestamp.from(now)).update() == 0) return Outcome.duplicate();
    String fingerprint;
    try { validateEnvelope(e); fingerprint = sha256(e.messageType() + ":" + e.payload()); }
    catch (AcademicRejectedException ex) { return reject(e, ex.code(), attemptedId(e), now); }
    Idempotency old = jdbc.sql("SELECT message_type,request_fingerprint,aggregate_id,aggregate_version,result_status,error_code FROM idempotency_records WHERE producer=:producer AND idempotency_key=:key FOR UPDATE")
        .param("producer", e.producer()).param("key", e.idempotencyKey()).query((rs, n) -> new Idempotency(rs.getString(1), rs.getString(2), rs.getObject(3, UUID.class), (Integer) rs.getObject(4), rs.getString(5), rs.getString(6))).optional().orElse(null);
    if (old != null) {
      if (!old.type.equals(e.messageType()) || !old.fingerprint.equals(fingerprint)) return reject(e, "INVALID_MESSAGE", null, now);
      appendResult(e, old.aggregateId, old.version, old.status, old.error, now);
      return new Outcome(old.status, old.error, old.aggregateId, old.version);
    }
    try {
      requireAdmin(e);
      Object savepoint = TransactionAspectSupport.currentTransactionStatus().createSavepoint();
      Change change;
      try {
        change = handle(e, now);
        TransactionAspectSupport.currentTransactionStatus().releaseSavepoint(savepoint);
      } catch (AcademicRejectedException ex) {
        TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
        return reject(e, ex.code(), attemptedId(e), now);
      } catch (DuplicateKeyException ex) {
        // PostgreSQL requires rolling back the failed insert before the rejection Result can commit.
        TransactionAspectSupport.currentTransactionStatus().rollbackToSavepoint(savepoint);
        return reject(e, duplicateCode(e.messageType(), ex), attemptedId(e), now);
      }
      appendResult(e, change.id, change.version, "SUCCEEDED", null, now);
      for (Event event : change.events) appendEvent(e, event, now);
      jdbc.sql("INSERT INTO idempotency_records (producer,idempotency_key,message_type,request_fingerprint,aggregate_id,aggregate_version,result_status,created_at,updated_at) VALUES (:producer,:key,:type,:fingerprint,:id,:version,'SUCCEEDED',:now,:now)")
          .param("producer", e.producer()).param("key", e.idempotencyKey()).param("type", e.messageType()).param("fingerprint", fingerprint).param("id", change.id).param("version", change.version).param("now", java.sql.Timestamp.from(now)).update();
      return new Outcome("SUCCEEDED", null, change.id, change.version);
    } catch (AcademicRejectedException ex) { return reject(e, ex.code(), attemptedId(e), now); }
  }

  private Outcome reject(MessageEnvelope e, String code, UUID id, Instant now) {
    appendResult(e, id, null, "REJECTED", code, now);
    jdbc.sql("INSERT INTO idempotency_records (producer,idempotency_key,message_type,request_fingerprint,aggregate_id,result_status,error_code,created_at,updated_at) VALUES (:producer,:key,:type,:fingerprint,:id,'REJECTED',:code,:now,:now) ON CONFLICT DO NOTHING")
        .param("producer", e.producer()).param("key", e.idempotencyKey()).param("type", e.messageType()).param("fingerprint", sha256(e.messageType() + ":" + e.payload())).param("id", id).param("code", code).param("now", java.sql.Timestamp.from(now)).update();
    return new Outcome("REJECTED", code, id, null);
  }

  private Change handle(MessageEnvelope e, Instant now) {
    return switch (e.messageType()) {
      case "CreateStudentAcademicProfile" -> createProfile(e, now);
      case "UpdateStudentAcademicProfile" -> updateProfile(e, now);
      case "CorrectStudentNumber" -> correctStudentNumber(e, now);
      case "ChangeStudentSemester" -> changeSemester(e, now);
      case "ActivateStudentAcademicProfile" -> changeProfileStatus(e, now, "ACTIVE");
      case "DeactivateStudentAcademicProfile" -> changeProfileStatus(e, now, "INACTIVE");
      case "CreateAndActivateAcademicPeriod" -> createAndActivatePeriod(e, now);
      case "RegisterCourseSection" -> registerSection(e, now);
      case "UpdateCourseSection" -> updateSection(e, now);
      case "ActivateCourseSection" -> changeSectionStatus(e, now, "ACTIVE");
      case "DeactivateCourseSection" -> changeSectionStatus(e, now, "INACTIVE");
      case "AddStudentEnrollment" -> addEnrollment(e, now);
      case "ReactivateStudentEnrollment" -> reactivateEnrollment(e, now);
      case "RemoveStudentEnrollment" -> removeEnrollment(e, now);
      default -> throw new AcademicRejectedException("INVALID_MESSAGE");
    };
  }

  private Change createProfile(MessageEnvelope e, Instant now) {
    JsonNode p = e.payload(); UUID id = uuid(p, "studentAcademicProfileId", UUID.randomUUID()); UUID person = uuid(p, "personId", null); UUID account = uuid(p, "accountId", null);
    int semester = integer(p, "currentSemester"); String status = text(p, "academicStatus", "ACTIVE"); String number = required(p, "studentNumber");
    validateSemester(semester);
    if (!status.equals("ACTIVE") && !status.equals("INACTIVE")) throw new AcademicRejectedException("INVALID_ACADEMIC_STATUS");
    if (!projectionActive("person_status_projection", "person_id", person)) throw new AcademicRejectedException("PERSON_NOT_FOUND");
    if (!projectionActive("account_status_projection", "account_id", account) || !isStudentAccount(account)) throw new AcademicRejectedException("ACCOUNT_NOT_ELIGIBLE_AS_STUDENT");
    jdbc.sql("INSERT INTO student_academic_profiles (student_academic_profile_id,person_id,account_id,student_number,student_number_normalized,current_semester,academic_status,version,student_number_version,created_at,updated_at) VALUES (:id,:person,:account,:number,:normalized,:semester,CAST(:status AS academic_profile_status),1,0,:now,:now)")
        .param("id", id).param("person", person).param("account", account).param("number", number.trim()).param("normalized", normalize(number)).param("semester", semester).param("status", status).param("now", java.sql.Timestamp.from(now)).update();
    var events = events(new Event("StudentAcademicProfileCreated", id, 1, map("studentAcademicProfileId", id, "personId", person, "accountId", account, "studentNumber", number, "currentSemester", semester, "academicStatus", status, "createdAt", now)));
    events.add(evaluateEligibility(id, account, semester, status, 1, e, now));
    return new Change(id, 1, events);
  }

  private Change updateProfile(MessageEnvelope e, Instant now) {
    UUID id = uuid(e.payload(), "studentAcademicProfileId", null); Profile profile = profile(id);
    expected(e.payload(), profile.version); int version = profile.version + 1;
    expectedUpdate(jdbc.sql("UPDATE student_academic_profiles SET version=:version,updated_at=:now WHERE student_academic_profile_id=:id AND version=:expected").param("version", version).param("now", java.sql.Timestamp.from(now)).param("id", id).param("expected", profile.version).update());
    var events = events(new Event("StudentAcademicProfileUpdated", id, version, map("studentAcademicProfileId", id, "personId", profile.personId, "accountId", profile.accountId, "updatedAt", now)));
    Event eligibility = evaluateEligibility(id, profile.accountId, profile.semester, profile.status, version, e, now); if (eligibility != null) events.add(eligibility);
    return new Change(id, version, events);
  }

  private Change correctStudentNumber(MessageEnvelope e, Instant now) {
    JsonNode p = e.payload(); UUID id = uuid(p, "studentAcademicProfileId", null); Profile profile = profile(id); expected(p, profile.version);
    requireActorField(p, "correctedByAccountId", e);
    String current = required(p, "currentStudentNumber"), replacement = required(p, "newStudentNumber"), reason = required(p, "reason");
    if (!normalize(profile.number).equals(normalize(current))) throw new AcademicRejectedException("STUDENT_NUMBER_MISMATCH");
    int version = profile.version + 1, numberVersion = profile.numberVersion + 1;
    expectedUpdate(jdbc.sql("UPDATE student_academic_profiles SET student_number=:number,student_number_normalized=:normalized,version=:version,student_number_version=:numberVersion,updated_at=:now WHERE student_academic_profile_id=:id AND version=:expected")
        .param("number", replacement.trim()).param("normalized", normalize(replacement)).param("version", version).param("numberVersion", numberVersion).param("now", java.sql.Timestamp.from(now)).param("id", id).param("expected", profile.version).update());
    jdbc.sql("INSERT INTO student_number_change_history (change_id,student_academic_profile_id,previous_student_number,new_student_number,reason,corrected_by_account_id,correlation_id,corrected_at) VALUES (:change,:id,:old,:new,:reason,:actor,:correlation,:now)")
        .param("change", UUID.randomUUID()).param("id", id).param("old", current).param("new", replacement).param("reason", reason).param("actor", e.actor().accountId()).param("correlation", e.correlationId()).param("now", java.sql.Timestamp.from(now)).update();
    return new Change(id, version, events(new Event("StudentNumberCorrected", id, version, map("studentAcademicProfileId", id, "accountId", profile.accountId, "previousStudentNumber", current, "newStudentNumber", replacement, "reason", reason, "correctedByAccountId", e.actor().accountId(), "correctedAt", now, "studentNumberVersion", numberVersion))));
  }

  private Change changeSemester(MessageEnvelope e, Instant now) {
    UUID id = uuid(e.payload(), "studentAcademicProfileId", null); Profile profile = profile(id); expected(e.payload(), profile.version); int semester = integer(e.payload(), "currentSemester");
    validateSemester(semester); int version = profile.version + 1;
    expectedUpdate(jdbc.sql("UPDATE student_academic_profiles SET current_semester=:semester,version=:version,updated_at=:now WHERE student_academic_profile_id=:id AND version=:expected").param("semester", semester).param("version", version).param("now", java.sql.Timestamp.from(now)).param("id", id).param("expected", profile.version).update());
    var events = events(new Event("StudentSemesterChanged", id, version, map("studentAcademicProfileId", id, "accountId", profile.accountId, "previousSemester", profile.semester, "currentSemester", semester, "changedAt", now)));
    Event eligibility = evaluateEligibility(id, profile.accountId, semester, profile.status, version, e, now); if (eligibility != null) events.add(eligibility);
    return new Change(id, version, events);
  }

  private Change changeProfileStatus(MessageEnvelope e, Instant now, String target) {
    UUID id = uuid(e.payload(), "studentAcademicProfileId", null); Profile profile = profile(id); expected(e.payload(), profile.version); int version = profile.version + 1;
    expectedUpdate(jdbc.sql("UPDATE student_academic_profiles SET academic_status=CAST(:status AS academic_profile_status),version=:version,updated_at=:now WHERE student_academic_profile_id=:id AND version=:expected").param("status", target).param("version", version).param("now", java.sql.Timestamp.from(now)).param("id", id).param("expected", profile.version).update());
    String type = target.equals("ACTIVE") ? "StudentAcademicProfileActivated" : "StudentAcademicProfileDeactivated";
    Map<String,Object> data = map("studentAcademicProfileId", id, "accountId", profile.accountId, "previousStatus", profile.status, "academicStatus", target, target.equals("ACTIVE") ? "activatedAt" : "deactivatedAt", now);
    if (target.equals("INACTIVE") && e.payload().hasNonNull("reason")) data.put("reason", text(e.payload(), "reason", null));
    var events = events(new Event(type, id, version, data)); Event eligibility = evaluateEligibility(id, profile.accountId, profile.semester, target, version, e, now); if (eligibility != null) events.add(eligibility);
    return new Change(id, version, events);
  }

  private Change createAndActivatePeriod(MessageEnvelope e, Instant now) {
    JsonNode p = e.payload(); requireActorField(p, "requestedByAccountId", e); requireWarningAcknowledged(p); String code = required(p, "code"), name = required(p, "name"); LocalDate start = LocalDate.parse(required(p, "startDate")), end = LocalDate.parse(required(p, "endDate"));
    if (end.isBefore(start)) throw new AcademicRejectedException("INVALID_PERIOD_DATES");
    UUID id = uuid(p, "academicPeriodId", UUID.randomUUID()); Period old = activePeriod(false); var events = events();
    if (old != null) { expectedUpdate(jdbc.sql("UPDATE academic_periods SET status='CLOSED',version=version+1,updated_at=:now WHERE academic_period_id=:id AND version=:expected AND status='ACTIVE'").param("id", old.id).param("expected", old.version).param("now", java.sql.Timestamp.from(now)).update()); events.add(new Event("AcademicPeriodClosed", old.id, old.version + 1, map("academicPeriodId", old.id, "code", old.code, "status", "CLOSED", "closedByActivationOfAcademicPeriodId", id, "closedAt", now))); }
    jdbc.sql("INSERT INTO academic_periods (academic_period_id,code,name,start_date,end_date,status,academic_data_status,version,created_at,updated_at) VALUES (:id,:code,:name,:start,:end,'ACTIVE','PENDING_IMPORT',1,:now,:now)")
        .param("id", id).param("code", code).param("name", name).param("start", start).param("end", end).param("now", java.sql.Timestamp.from(now)).update();
    events.add(new Event("AcademicPeriodActivated", id, 1, map("academicPeriodId", id, "code", code, "name", name, "startDate", start, "endDate", end, "status", "ACTIVE", "academicDataStatus", "PENDING_IMPORT", "activatedByAccountId", e.actor().accountId(), "activatedAt", now, "warningAcknowledged", p.path("warningAcknowledged").asBoolean(false))));
    events.add(new Event("AcademicPeriodDataStatusChanged", id, 1, map("academicPeriodId", id, "previousAcademicDataStatus", null, "academicDataStatus", "PENDING_IMPORT", "reason", "PERIOD_ACTIVATED", "changedAt", now)));
    return new Change(id, 1, events);
  }

  private Change registerSection(MessageEnvelope e, Instant now) {
    JsonNode p = e.payload(); UUID periodId = uuid(p, "academicPeriodId", null); readyPeriod(periodId); UUID id = uuid(p, "courseSectionId", UUID.randomUUID()); String nrc = required(p, "nrc"), name = required(p, "name");
    jdbc.sql("INSERT INTO course_sections (course_section_id,academic_period_id,nrc,name,status,version,created_at,updated_at) VALUES (:id,:period,:nrc,:name,'ACTIVE',1,:now,:now)").param("id", id).param("period", periodId).param("nrc", nrc).param("name", name).param("now", java.sql.Timestamp.from(now)).update();
    return new Change(id, 1, events(new Event("CourseSectionRegistered", id, 1, map("courseSectionId", id, "academicPeriodId", periodId, "nrc", nrc, "name", name, "status", "ACTIVE", "registeredAt", now))));
  }

  private Change updateSection(MessageEnvelope e, Instant now) {
    UUID id = uuid(e.payload(), "courseSectionId", null); Section section = section(id); readyPeriod(section.periodId); expected(e.payload(), section.version); int version = section.version + 1; String name = required(e.payload(), "name");
    expectedUpdate(jdbc.sql("UPDATE course_sections SET name=:name,version=:version,updated_at=:now WHERE course_section_id=:id AND version=:expected").param("name", name).param("version", version).param("now", java.sql.Timestamp.from(now)).param("id", id).param("expected", section.version).update());
    return new Change(id, version, events(new Event("CourseSectionUpdated", id, version, map("courseSectionId", id, "academicPeriodId", section.periodId, "nrc", section.nrc, "name", name, "updatedAt", now))));
  }

  private Change changeSectionStatus(MessageEnvelope e, Instant now, String target) {
    UUID id = uuid(e.payload(), "courseSectionId", null); Section section = section(id); readyPeriod(section.periodId); expected(e.payload(), section.version);
    if (e.payload().hasNonNull("academicPeriodId") && !section.periodId.equals(uuid(e.payload(), "academicPeriodId", null))) throw new AcademicRejectedException("ACADEMIC_PERIOD_NOT_FOUND");
    if (target.equals("ACTIVE") && e.payload().hasNonNull("name") && !section.name.equals(text(e.payload(), "name", null))) throw new AcademicRejectedException("COURSE_SECTION_NAME_CONFLICT");
    if (section.status.equals(target)) throw new AcademicRejectedException(target.equals("ACTIVE") ? "COURSE_SECTION_ALREADY_ACTIVE" : "COURSE_SECTION_ALREADY_INACTIVE"); int version = section.version + 1;
    expectedUpdate(jdbc.sql("UPDATE course_sections SET status=CAST(:status AS course_section_status),version=:version,updated_at=:now WHERE course_section_id=:id AND version=:expected").param("status", target).param("version", version).param("now", java.sql.Timestamp.from(now)).param("id", id).param("expected", section.version).update());
    return new Change(id, version, events(new Event("CourseSectionStatusChanged", id, version, map("courseSectionId", id, "academicPeriodId", section.periodId, "nrc", section.nrc, "previousStatus", section.status, "currentStatus", target, "changedByAccountId", e.actor().accountId(), "reason", text(e.payload(), "reason", null), "changedAt", now))));
  }

  private Change addEnrollment(MessageEnvelope e, Instant now) {
    UUID student = uuid(e.payload(), "studentAcademicProfileId", null), course = uuid(e.payload(), "courseSectionId", null); Profile profile = profile(student); if (!profile.status.equals("ACTIVE")) throw new AcademicRejectedException("STUDENT_PROFILE_INACTIVE"); Section section = section(course); readyPeriod(section.periodId); if (!section.status.equals("ACTIVE")) throw new AcademicRejectedException("COURSE_SECTION_INACTIVE");
    jdbc.sql("INSERT INTO student_course_sections (student_academic_profile_id,course_section_id,status,enrolled_at,version,updated_at) VALUES (:student,:course,'ACTIVE',:now,1,:now)").param("student", student).param("course", course).param("now", java.sql.Timestamp.from(now)).update();
    return new Change(student, 1, events(new Event("StudentEnrolledInCourseSection", student, 1, map("studentAcademicProfileId", student, "accountId", profile.accountId, "courseSectionId", course, "academicPeriodId", section.periodId, "nrc", section.nrc, "enrolledAt", now))));
  }

  private Change reactivateEnrollment(MessageEnvelope e, Instant now) { return changeEnrollment(e, now, "ACTIVE", "StudentEnrollmentReactivated"); }
  private Change removeEnrollment(MessageEnvelope e, Instant now) { return changeEnrollment(e, now, "REMOVED", "StudentRemovedFromCourseSection"); }
  private Change changeEnrollment(MessageEnvelope e, Instant now, String target, String eventType) {
    UUID student = uuid(e.payload(), "studentAcademicProfileId", null), course = uuid(e.payload(), "courseSectionId", null); Enrollment enrollment = enrollment(student, course); Profile profile = profile(student); Section section = section(course); readyPeriod(section.periodId);
    if (enrollment.status.equals(target)) throw new AcademicRejectedException(target.equals("ACTIVE") ? "ENROLLMENT_ALREADY_EXISTS" : "ENROLLMENT_ALREADY_REMOVED"); expected(e.payload(), enrollment.version); int version = enrollment.version + 1;
    expectedUpdate(jdbc.sql("UPDATE student_course_sections SET status=CAST(:status AS enrollment_status),removed_at=:removed,version=:version,updated_at=:now WHERE student_academic_profile_id=:student AND course_section_id=:course AND version=:expected")
        .param("status", target).param("removed", target.equals("REMOVED") ? java.sql.Timestamp.from(now) : null).param("version", version).param("now", java.sql.Timestamp.from(now)).param("student", student).param("course", course).param("expected", enrollment.version).update());
    Map<String,Object> data = map("studentAcademicProfileId", student, "accountId", profile.accountId, "courseSectionId", course, "academicPeriodId", section.periodId, "nrc", section.nrc, target.equals("ACTIVE") ? "reactivatedAt" : "removedAt", now);
    if (target.equals("REMOVED") && e.payload().hasNonNull("reason")) data.put("reason", text(e.payload(), "reason", null));
    return new Change(student, version, events(new Event(eventType, student, version, data)));
  }

  private Event evaluateEligibility(UUID profileId, UUID accountId, int semester, String status, int profileVersion, MessageEnvelope cause, Instant now) {
    AcademicEligibilityPolicyV1 policy = eligibilityPolicies.active();
    boolean eligible = policy.isEligible(new StudentAcademicProfile(profileId, profileId, accountId, "evaluation", semester, StudentAcademicProfile.Status.valueOf(status), profileVersion));
    Eligibility old = jdbc.sql("SELECT eligibility_id,eligible,policy_version,eligibility_version FROM student_academic_eligibilities WHERE student_academic_profile_id=:profile AND system_code=:system FOR UPDATE").param("profile", profileId).param("system", AcademicEligibilityPolicyV1.SYSTEM_CODE).query((rs,n)->new Eligibility(rs.getObject(1,UUID.class),rs.getBoolean(2),rs.getInt(3),rs.getInt(4))).optional().orElse(null);
    if (old != null && old.eligible == eligible && old.policyVersion == policy.version()) return null;
    UUID id = old == null ? UUID.randomUUID() : old.id; int version = old == null ? 1 : old.version + 1;
    if (old == null) jdbc.sql("INSERT INTO student_academic_eligibilities (eligibility_id,student_academic_profile_id,system_code,eligible,policy_version,eligibility_version,academic_profile_version,evaluated_at,updated_at) VALUES (:id,:profile,:system,:eligible,:policy,1,:profileVersion,:now,:now)").param("id",id).param("profile",profileId).param("system",AcademicEligibilityPolicyV1.SYSTEM_CODE).param("eligible",eligible).param("policy",policy.version()).param("profileVersion",profileVersion).param("now",java.sql.Timestamp.from(now)).update();
    else jdbc.sql("UPDATE student_academic_eligibilities SET eligible=:eligible,policy_version=:policy,eligibility_version=:version,academic_profile_version=:profileVersion,evaluated_at=:now,updated_at=:now WHERE eligibility_id=:id AND eligibility_version=:expected").param("eligible",eligible).param("policy",policy.version()).param("version",version).param("profileVersion",profileVersion).param("now",java.sql.Timestamp.from(now)).param("id",id).param("expected",old.version).update();
    return new Event("StudentAcademicEligibilityChanged", id, version, map("eligibilityId", id, "studentAcademicProfileId", profileId, "accountId", accountId, "systemCode", AcademicEligibilityPolicyV1.SYSTEM_CODE, "eligible", eligible, "policyVersion", policy.version(), "eligibilityVersion", version, "academicProfileVersion", profileVersion, "evaluatedAt", now));
  }

  private void requireAdmin(MessageEnvelope e) {
    if (e.actor() == null || !"ACCOUNT".equals(e.actor().type()) || e.actor().accountId() == null) throw new AcademicRejectedException("ACTOR_NOT_AUTHORIZED");
    boolean allowed = jdbc.sql("SELECT EXISTS (SELECT 1 FROM authorization_context_projection WHERE account_id=:id AND role_code='SUPERADMIN' AND status='ACTIVE')")
        .param("id", e.actor().accountId()).query(Boolean.class).single();
    if (!allowed) throw new AcademicRejectedException("ACTOR_NOT_AUTHORIZED");
  }

  private boolean projectionActive(String table, String column, UUID id) {
    if (id == null) return false;
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM " + table + " WHERE " + column + "=:id AND status='ACTIVE')").param("id", id).query(Boolean.class).single();
  }
  private boolean isStudentAccount(UUID account) {
    return jdbc.sql("SELECT EXISTS (SELECT 1 FROM authorization_context_projection WHERE account_id=:id AND role_code='ALUMNO' AND status='ACTIVE')").param("id", account).query(Boolean.class).single();
  }
  private Profile profile(UUID id) {
    if (id == null) throw new AcademicRejectedException("STUDENT_PROFILE_NOT_FOUND");
    return jdbc.sql("SELECT person_id,account_id,student_number,current_semester,academic_status::text,version,student_number_version FROM student_academic_profiles WHERE student_academic_profile_id=:id")
        .param("id", id).query((rs,n)->new Profile(id,rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getString(3),rs.getInt(4),rs.getString(5),rs.getInt(6),rs.getInt(7))).optional().orElseThrow(()->new AcademicRejectedException("STUDENT_PROFILE_NOT_FOUND"));
  }
  private Section section(UUID id) {
    if (id == null) throw new AcademicRejectedException("COURSE_SECTION_NOT_FOUND");
    return jdbc.sql("SELECT academic_period_id,nrc,name,status::text,version FROM course_sections WHERE course_section_id=:id").param("id", id)
        .query((rs,n)->new Section(id,rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5))).optional().orElseThrow(()->new AcademicRejectedException("COURSE_SECTION_NOT_FOUND"));
  }
  private Enrollment enrollment(UUID student, UUID course) {
    return jdbc.sql("SELECT status::text,version FROM student_course_sections WHERE student_academic_profile_id=:student AND course_section_id=:course").param("student",student).param("course",course)
        .query((rs,n)->new Enrollment(rs.getString(1),rs.getInt(2))).optional().orElseThrow(()->new AcademicRejectedException("ENROLLMENT_NOT_FOUND"));
  }
  private Period activePeriod(boolean required) {
    Period period = jdbc.sql("SELECT academic_period_id,code,status::text,academic_data_status::text,version FROM academic_periods WHERE status='ACTIVE'")
        .query((rs,n)->new Period(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5))).optional().orElse(null);
    if (period == null && required) throw new AcademicRejectedException("NO_ACTIVE_ACADEMIC_PERIOD");
    return period;
  }
  private void readyPeriod(UUID id) {
    if (id == null) throw new AcademicRejectedException("ACADEMIC_PERIOD_NOT_FOUND");
    Period period = jdbc.sql("SELECT academic_period_id,code,status::text,academic_data_status::text,version FROM academic_periods WHERE academic_period_id=:id").param("id",id)
        .query((rs,n)->new Period(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getString(4),rs.getInt(5))).optional().orElseThrow(()->new AcademicRejectedException("ACADEMIC_PERIOD_NOT_FOUND"));
    if (period.status.equals("CLOSED")) throw new AcademicRejectedException("ACADEMIC_PERIOD_CLOSED");
    if (!period.dataStatus.equals("READY")) throw new AcademicRejectedException("ACADEMIC_PERIOD_NOT_READY");
  }
  private void expected(JsonNode payload, int actual) {
    if (!payload.hasNonNull("expectedVersion") || payload.path("expectedVersion").asInt(Integer.MIN_VALUE) != actual) throw new AcademicRejectedException("VERSION_CONFLICT");
  }
  private static void expectedUpdate(int rows) { if (rows != 1) throw new AcademicRejectedException("VERSION_CONFLICT"); }
  private static void validateSemester(int semester) { try { StudentAcademicProfile.validateSemester(semester); } catch (IllegalArgumentException ex) { throw new AcademicRejectedException("INVALID_SEMESTER"); } }
  private static void requireActorField(JsonNode payload, String field, MessageEnvelope envelope) {
    UUID requested = uuid(payload, field, null);
    if (requested == null || envelope.actor() == null || !requested.equals(envelope.actor().accountId())) throw new AcademicRejectedException("ACTOR_NOT_AUTHORIZED");
  }
  private static void requireWarningAcknowledged(JsonNode payload) {
    if (!payload.has("warningAcknowledged") || !payload.path("warningAcknowledged").isBoolean() || !payload.path("warningAcknowledged").asBoolean()) throw new AcademicRejectedException("WARNING_NOT_ACKNOWLEDGED");
  }
  private void validateEnvelope(MessageEnvelope e) {
    if (e == null || e.messageId() == null || e.correlationId() == null || e.idempotencyKey() == null || e.idempotencyKey().isBlank() || e.payload() == null || !"COMMAND".equals(e.messageKind()) || e.messageVersion() != 1 || !"bff".equals(e.producer())) throw new AcademicRejectedException("INVALID_MESSAGE");
  }
  private void appendResult(MessageEnvelope e, UUID id, Integer version, String status, String code, Instant now) {
    Map<String,Object> payload = map("status", status); if ("SUCCEEDED".equals(status)) payload.put("data", map("aggregateId", id, "aggregateVersion", version)); else payload.put("error", map("code", code));
    outbox.append(UUID.randomUUID(), e.messageType() + "Result", "RESULT", id, version, e.correlationId(), e.messageId(), codec.json(payload), codec.json(map("destination", e.producer(), "operationId", e.operationId(), "actor", e.actor())), now);
  }
  private void appendEvent(MessageEnvelope e, Event event, Instant now) {
    outbox.append(UUID.randomUUID(), event.type, "EVENT", event.id, event.version, e.correlationId(), e.messageId(), codec.json(event.payload), codec.json(map("actor", e.actor())), now);
  }
  private static UUID attemptedId(MessageEnvelope e) { return e.aggregateId() == null ? uuid(e.payload(), "studentAcademicProfileId", null) : e.aggregateId(); }
  private static String duplicateCode(String type, DuplicateKeyException exception) {
    if ("CreateStudentAcademicProfile".equals(type) && exception.getMostSpecificCause().getMessage().contains("student_number_normalized")) return "STUDENT_NUMBER_ALREADY_EXISTS";
    return switch (type) { case "CreateStudentAcademicProfile" -> "STUDENT_PROFILE_ALREADY_EXISTS"; case "CorrectStudentNumber" -> "STUDENT_NUMBER_ALREADY_EXISTS"; case "CreateAndActivateAcademicPeriod" -> "ACADEMIC_PERIOD_ALREADY_EXISTS"; case "RegisterCourseSection" -> "COURSE_SECTION_ALREADY_EXISTS"; case "AddStudentEnrollment" -> "ENROLLMENT_ALREADY_EXISTS"; default -> "VERSION_CONFLICT"; };
  }
  private static String required(JsonNode p, String field) { String value = text(p, field, null); if (value == null || value.isBlank()) throw new AcademicRejectedException("INVALID_MESSAGE"); return value; }
  private static String text(JsonNode p, String field, String defaultValue) { return p.hasNonNull(field) ? p.path(field).asString() : defaultValue; }
  private static int integer(JsonNode p, String field) { if (!p.hasNonNull(field)) throw new AcademicRejectedException("INVALID_MESSAGE"); return p.path(field).asInt(); }
  private static UUID uuid(JsonNode p, String field, UUID defaultValue) { if (!p.hasNonNull(field)) return defaultValue; try { return UUID.fromString(p.path(field).asString()); } catch (RuntimeException ex) { throw new AcademicRejectedException("INVALID_MESSAGE"); } }
  private static String normalize(String value) { try { return StudentAcademicProfile.normalizedStudentNumber(value); } catch (IllegalArgumentException ex) { throw new AcademicRejectedException("INVALID_MESSAGE"); } }
  private static String sha256(String value) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (Exception ex) { throw new IllegalStateException(ex); } }
  private static Map<String,Object> map(Object... entries) { Map<String,Object> result = new LinkedHashMap<>(); for (int i=0;i<entries.length;i+=2) result.put((String)entries[i], entries[i+1]); return result; }
  private static java.util.List<Event> events(Event... values) { java.util.List<Event> result = new java.util.ArrayList<>(); for (Event value : values) if (value != null) result.add(value); return result; }
  public record Outcome(String status, String errorCode, UUID aggregateId, Integer aggregateVersion) { static Outcome duplicate() { return new Outcome("DUPLICATE", null, null, null); } }
  private record Change(UUID id, int version, java.util.List<Event> events) {}
  private record Event(String type, UUID id, int version, Map<String,Object> payload) {}
  private record Idempotency(String type, String fingerprint, UUID aggregateId, Integer version, String status, String error) {}
  private record Profile(UUID id, UUID personId, UUID accountId, String number, int semester, String status, int version, int numberVersion) {}
  private record Period(UUID id, String code, String status, String dataStatus, int version) {}
  private record Section(UUID id, UUID periodId, String nrc, String name, String status, int version) {}
  private record Enrollment(String status, int version) {}
  private record Eligibility(UUID id, boolean eligible, int policyVersion, int version) {}
}
