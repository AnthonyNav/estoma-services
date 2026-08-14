package com.estoma.academic.application;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.domain.StudentAcademicProfile;
import com.estoma.academic.messaging.EnvelopeCodec;
import com.estoma.academic.persistence.OutboxRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Builds a complete immutable import plan from persisted validation facts and local projections. */
@Component
public class StudentImportPlanBuilder {
  private static final String PERSON_RESOLUTION = "AUTHORITATIVE_PERSON_RESOLUTION";
  private static final String LOCAL_FILE_ACCESS = "LOCAL_FILE_ACCESS";
  private static final Actor SYSTEM_ACTOR = new Actor("SYSTEM", null, "academic");

  private final JdbcClient jdbc;
  private final EnvelopeCodec codec;
  private final OutboxRepository outbox;
  private final StudentImportValidationRejectionService rejectionService;

  public StudentImportPlanBuilder(
      JdbcClient jdbc,
      EnvelopeCodec codec,
      OutboxRepository outbox,
      StudentImportValidationRejectionService rejectionService) {
    this.jdbc = jdbc;
    this.codec = codec;
    this.outbox = outbox;
    this.rejectionService = rejectionService;
  }

  @Transactional
  public boolean buildIfReady(UUID importId) {
    Process process = jdbc.sql("""
        SELECT import_id,academic_period_id,academic_period_version,process_version,status::text
        FROM student_import_processes WHERE import_id=:importId FOR UPDATE
        """).param("importId", importId)
        .query((rs, n) -> new Process(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getInt(3), rs.getInt(4), rs.getString(5)))
        .optional().orElse(null);
    if (process == null) throw new IllegalArgumentException("student import does not exist");
    if ("VALIDATED".equals(process.status)) return true;
    if (!"VALIDATING".equals(process.status) || !workSucceeded(importId, LOCAL_FILE_ACCESS)) return false;
    Map<String, Resolution> resolutions = resolutions(importId);
    if (resolutions == null) return false;

    try {
      List<Student> students = students(importId);
      if (!supportedAndComplete(students, resolutions)) return false;
      List<Plan> plans = plans(process, students, resolutions);
      if (jdbc.sql("SELECT count(*) FROM student_import_plan_items WHERE import_id=:importId")
          .param("importId", importId).query(Long.class).single() != 0) {
        return false;
      }
      Instant now = Instant.now();
      for (Plan plan : plans) insert(importId, plan, now);
      Summary summary = summary(plans);
      int changed = jdbc.sql("""
          UPDATE student_import_processes
          SET status='VALIDATED',process_version=:version,validated_at=:now,
              failure_code=NULL,failure_reason=NULL,failed_step=NULL,recoverable=NULL,
              students_created=:studentsCreated,students_updated=:studentsUpdated,
              students_reactivated=:studentsReactivated,students_deactivated=:studentsDeactivated,
              course_sections_created=:courseSectionsCreated,course_sections_activated=:courseSectionsActivated,
              course_sections_deactivated=:courseSectionsDeactivated,enrollments_registered=:enrollmentsRegistered,
              enrollments_reactivated=:enrollmentsReactivated,enrollments_removed=:enrollmentsRemoved,updated_at=:now
          WHERE import_id=:importId AND status='VALIDATING' AND process_version=:expected
          """).param("version", process.version + 1).param("expected", process.version)
          .param("studentsCreated", summary.studentsCreated).param("studentsUpdated", summary.studentsUpdated)
          .param("studentsReactivated", summary.studentsReactivated).param("studentsDeactivated", summary.studentsDeactivated)
          .param("courseSectionsCreated", summary.courseSectionsCreated).param("courseSectionsActivated", summary.courseSectionsActivated)
          .param("courseSectionsDeactivated", summary.courseSectionsDeactivated).param("enrollmentsRegistered", summary.enrollmentsRegistered)
          .param("enrollmentsReactivated", summary.enrollmentsReactivated).param("enrollmentsRemoved", summary.enrollmentsRemoved)
          .param("importId", importId).param("now", java.sql.Timestamp.from(now)).update();
      if (changed != 1) throw new IllegalStateException("student import changed while its plan was being built");
      outbox.append(UUID.randomUUID(), "StudentImportValidated", "EVENT", importId, process.version + 1, importId, null,
          codec.json(key("importId", importId, "academicPeriodId", process.periodId,
              "studentsCreated", summary.studentsCreated, "studentsUpdated", summary.studentsUpdated,
              "studentsReactivated", summary.studentsReactivated, "studentsDeactivated", summary.studentsDeactivated,
              "courseSectionsCreated", summary.courseSectionsCreated, "courseSectionsActivated", summary.courseSectionsActivated,
              "courseSectionsDeactivated", summary.courseSectionsDeactivated, "enrollmentsRegistered", summary.enrollmentsRegistered,
              "enrollmentsReactivated", summary.enrollmentsReactivated, "enrollmentsRemoved", summary.enrollmentsRemoved,
              "validatedAt", now)), codec.json(Map.of("actor", SYSTEM_ACTOR)), now);
      return true;
    } catch (IdentityConflict ex) {
      rejectionService.rejectIdentityConflict(importId, ex.getMessage());
      return false;
    }
  }

  private boolean workSucceeded(UUID importId, String type) {
    return jdbc.sql("""
        SELECT EXISTS (SELECT 1 FROM student_import_validation_work
        WHERE import_id=:importId AND work_type=:type AND status='SUCCEEDED')
        """).param("importId", importId).param("type", type).query(Boolean.class).single();
  }

  private Map<String, Resolution> resolutions(UUID importId) {
    Map<String, Resolution> resolutions = new TreeMap<>();
    List<ResolutionWork> work = jdbc.sql("""
        SELECT work_key,result::text FROM student_import_validation_work
        WHERE import_id=:importId AND work_type=:type AND status='SUCCEEDED'
        ORDER BY work_key
        """).param("importId", importId).param("type", PERSON_RESOLUTION)
        .query((rs, n) -> new ResolutionWork(rs.getString(1), rs.getString(2))).list();
    for (ResolutionWork value : work) {
      JsonNode result = codec.node(value.result);
      String outcome = text(result, "outcome");
      if (value.email == null || outcome == null
          || resolutions.put(value.email, new Resolution(outcome, uuid(result, "personId"), text(result, "status"))) != null) {
        return null;
      }
    }
    return resolutions;
  }

  private List<Student> students(UUID importId) {
    return jdbc.sql("""
        SELECT sequence,row_data::text FROM student_import_validation_rows
        WHERE import_id=:importId AND row_type='STUDENT' ORDER BY sequence
        """).param("importId", importId).query((rs, n) -> {
          JsonNode row = codec.node(rs.getString(2));
          return new Student(rs.getInt(1), StudentAcademicProfile.normalizedStudentNumber(text(row, "studentNumber")), text(row, "firstName"),
              text(row, "paternalSurname"), text(row, "maternalSurname"), text(row, "institutionalEmail"), row.path("semester").asInt());
        }).list();
  }

  private boolean supportedAndComplete(List<Student> students, Map<String, Resolution> resolutions) {
    Set<String> emails = new HashSet<>();
    for (Student student : students) {
      if (student.email == null) throw new IdentityConflict("Imported student is missing an institutional email");
      if (!emails.add(student.email)) throw new IdentityConflict("Two imported students use the same institutional email");
      Resolution resolution = resolutions.get(student.email);
      if (resolution == null) return false;
      if (!("FOUND".equals(resolution.outcome) || "NOT_FOUND".equals(resolution.outcome))) {
        throw new IllegalStateException("unsupported person-resolution outcome " + resolution.outcome);
      }
      if ("FOUND".equals(resolution.outcome) && (resolution.personId == null || !"ACTIVE".equals(resolution.status))) {
        throw new IllegalStateException("FOUND person-resolution result is inconsistent");
      }
      if ("NOT_FOUND".equals(resolution.outcome) && (resolution.personId != null || resolution.status != null)) {
        throw new IllegalStateException("NOT_FOUND person-resolution result is inconsistent");
      }
    }
    return emails.size() == resolutions.size();
  }

  private List<Plan> plans(Process process, List<Student> students, Map<String, Resolution> resolutions) {
    List<Plan> plans = new ArrayList<>();
    Ids ids = new Ids();
    Map<String, Section> sections = sections(process.periodId);
    Map<String, Nrc> desiredSections = desiredSections(process.importId);
    for (Nrc nrc : desiredSections.values()) {
      Section section = sections.remove(nrc.nrc);
      if (section == null) {
        UUID id = ids.id("course-section", nrc.nrc);
        plans.add(plan(process.importId, "REGISTER_COURSE_SECTION", key("academicPeriodId", process.periodId, "nrc", nrc.nrc),
            key("courseSectionId", id, "academicPeriodId", process.periodId, "nrc", nrc.nrc, "name", nrc.name), Map.of(), id));
      } else if ("INACTIVE".equals(section.status)) {
        plans.add(plan(process.importId, "ACTIVATE_COURSE_SECTION", key("courseSectionId", section.id),
            key("courseSectionId", section.id, "academicPeriodId", process.periodId, "nrc", nrc.nrc, "name", nrc.name), versions("courseSectionVersion", section.version), null));
      }
    }
    for (Section section : sections.values()) {
      if ("ACTIVE".equals(section.status)) plans.add(plan(process.importId, "DEACTIVATE_COURSE_SECTION", key("courseSectionId", section.id),
          key("courseSectionId", section.id, "academicPeriodId", process.periodId, "nrc", section.nrc), versions("courseSectionVersion", section.version), null));
    }

    Map<String, Profile> profiles = profiles();
    Map<UUID, Profile> profilesByPerson = new HashMap<>();
    for (Profile profile : profiles.values()) profilesByPerson.put(profile.personId, profile);
    Map<String, UUID> studentProfileIds = new HashMap<>();
    Set<String> desiredNumbers = new HashSet<>();
    for (Student student : students) {
      if (!desiredNumbers.add(student.number)) throw new IllegalStateException("validated plan contains duplicate student number");
      Resolution resolution = resolutions.get(student.email);
      UUID personId = "FOUND".equals(resolution.outcome) ? resolution.personId : ids.id("person", student.email);
      Profile profile = profiles.get(student.number);
      if (profile != null && !profile.personId.equals(personId)) {
        throw new IdentityConflict("Student number and institutional email resolve to different Persons");
      }
      Profile otherProfile = profilesByPerson.get(personId);
      if (otherProfile != null && !otherProfile.number.equals(student.number)) {
        throw new IdentityConflict("Resolved Person is already linked to a different student number");
      }
      Account account = accountFor(personId);
      if (profile != null && (account == null || !profile.accountId.equals(account.id))) {
        throw new IdentityConflict("Existing academic profile points to an incompatible student Account");
      }
      if ("FOUND".equals(resolution.outcome)) {
        Person person = person(personId);
        plans.add(plan(process.importId, "UPDATE_PERSON", key("personId", personId),
            key("personId", personId, "firstName", student.firstName, "paternalSurname", student.paternalSurname,
                "maternalSurname", student.maternalSurname),
            person == null ? Map.of() : versions("personVersion", person.version), null));
      } else {
        plans.add(plan(process.importId, "CREATE_PERSON", key("institutionalEmail", student.email),
            key("personId", personId, "firstName", student.firstName, "paternalSurname", student.paternalSurname,
                "maternalSurname", student.maternalSurname, "institutionalEmail", student.email), Map.of(), personId));
      }
      if (account == null) {
        UUID accountId = ids.id("account", student.email);
        account = new Account(accountId, "MATRICULA", "INACTIVE", 0, null);
        plans.add(plan(process.importId, "CREATE_ACCOUNT", key("personId", personId),
            key("accountId", accountId, "personId", personId, "loginType", "MATRICULA", "loginIdentifier", student.number, "status", "INACTIVE"), Map.of(), accountId));
      } else if ("INACTIVE".equals(account.status) && "ACADEMIC_ABSENCE".equals(account.deactivationReasonCode)) {
        plans.add(plan(process.importId, "ACTIVATE_ACCOUNT", key("accountId", account.id),
            key("accountId", account.id), versions("accountVersion", account.version), null));
        account = new Account(account.id, account.loginType, "ACTIVE", account.version + 1, null);
      }
      Role role = role(account.id);
      if (role == null || !"ACTIVE".equals(role.status)) plans.add(plan(process.importId, "ASSIGN_STUDENT_ROLE", key("accountId", account.id),
          key("accountId", account.id, "roleCode", "ALUMNO"), role == null ? Map.of() : versions("roleVersion", role.version), null));
      Access access = access(account.id);
      if (access == null) {
        UUID accessId = ids.id("access", account.id.toString());
        plans.add(plan(process.importId, "GRANT_STUDENT_SYSTEM_ACCESS", key("accountId", account.id, "systemCode", "LAVADO_ULTRASONICO"),
            key("systemAccessId", accessId, "accountId", account.id, "systemCode", "LAVADO_ULTRASONICO"), Map.of(), accessId));
      }
      if (profile == null) {
        UUID profileId = ids.id("student-profile", student.number);
        profile = new Profile(profileId, personId, account.id, student.number, student.semester, "ACTIVE", 0);
        plans.add(plan(process.importId, "CREATE_STUDENT_PROFILE", key("studentNumber", student.number),
            key("studentAcademicProfileId", profileId, "personId", personId, "accountId", account.id,
                "studentNumber", student.number, "currentSemester", student.semester, "academicStatus", "ACTIVE"), Map.of(), profileId));
      } else {
        int profileVersionForSemester = profile.version;
        if ("INACTIVE".equals(profile.status)) {
          plans.add(plan(process.importId, "ACTIVATE_STUDENT_PROFILE", key("studentAcademicProfileId", profile.id),
              key("studentAcademicProfileId", profile.id), versions("studentAcademicProfileVersion", profile.version), null));
          profileVersionForSemester++;
        }
        if (profile.semester != student.semester) plans.add(plan(process.importId, "CHANGE_STUDENT_SEMESTER", key("studentAcademicProfileId", profile.id),
            key("studentAcademicProfileId", profile.id, "currentSemester", student.semester), versions("studentAcademicProfileVersion", profileVersionForSemester), null));
      }
      studentProfileIds.put(student.number, profile.id);
    }
    for (Profile profile : profiles.values()) {
      if (!desiredNumbers.contains(profile.number) && "ACTIVE".equals(profile.status)) {
        plans.add(plan(process.importId, "DEACTIVATE_STUDENT_PROFILE", key("studentAcademicProfileId", profile.id),
            key("studentAcademicProfileId", profile.id), versions("studentAcademicProfileVersion", profile.version), null));
        Account account = account(profile.accountId);
        if (account != null && "MATRICULA".equals(account.loginType) && "ACTIVE".equals(account.status)) {
          plans.add(plan(process.importId, "DEACTIVATE_ACCOUNT", key("accountId", account.id),
              key("accountId", account.id, "personId", profile.personId, "reasonCode", "ACADEMIC_ABSENCE"),
              versions("accountVersion", account.version), null));
        }
      }
    }
    Map<String, UUID> sectionIds = sectionIds(process.importId, desiredSections, ids);
    Map<String, Enrollment> enrollments = enrollments(process.periodId);
    Set<String> desiredEnrollments = new HashSet<>();
    for (EnrollmentRow enrollment : enrollmentRows(process.importId)) {
      UUID profileId = studentProfileIds.get(enrollment.studentNumber);
      UUID sectionId = sectionIds.get(enrollment.nrc);
      if (profileId == null || sectionId == null || !desiredEnrollments.add(enrollment.studentNumber + "\u0000" + enrollment.nrc)) {
        throw new IllegalStateException("validated enrollment cannot be mapped into the import plan");
      }
      Enrollment current = enrollments.remove(enrollment.studentNumber + "\u0000" + enrollment.nrc);
      if (current == null) {
        UUID id = ids.id("enrollment", enrollment.studentNumber + "\u0000" + enrollment.nrc);
        plans.add(plan(process.importId, "ADD_STUDENT_ENROLLMENT", key("studentAcademicProfileId", profileId, "courseSectionId", sectionId),
            key("enrollmentId", id, "studentAcademicProfileId", profileId, "courseSectionId", sectionId), Map.of(), id));
      } else if ("REMOVED".equals(current.status)) {
        plans.add(plan(process.importId, "REACTIVATE_STUDENT_ENROLLMENT", key("enrollmentId", current.id),
            key("enrollmentId", current.id, "studentAcademicProfileId", profileId, "courseSectionId", sectionId), versions("enrollmentVersion", current.version), null));
      }
    }
    for (Enrollment enrollment : enrollments.values()) if ("ACTIVE".equals(enrollment.status)) plans.add(plan(process.importId, "REMOVE_STUDENT_ENROLLMENT", key("enrollmentId", enrollment.id),
        key("enrollmentId", enrollment.id, "studentAcademicProfileId", enrollment.profileId, "courseSectionId", enrollment.sectionId), versions("enrollmentVersion", enrollment.version), null));
    plans.add(plan(process.importId, "FINALIZE_STUDENT_IMPORT", key("academicPeriodId", process.periodId), key("academicPeriodId", process.periodId), versions("academicPeriodVersion", process.periodVersion), null));
    return plans;
  }

  private Map<String, Nrc> desiredSections(UUID importId) {
    Map<String, Nrc> rows = new TreeMap<>();
    jdbc.sql("SELECT row_data::text FROM student_import_validation_rows WHERE import_id=:importId AND row_type='NRC' ORDER BY sequence")
        .param("importId", importId).query(String.class).list().forEach(value -> {
          JsonNode row = codec.node(value);
          rows.put(text(row, "nrc"), new Nrc(text(row, "nrc"), text(row, "name")));
        });
    return rows;
  }

  private Map<String, Section> sections(UUID periodId) { return jdbc.sql("SELECT course_section_id,nrc,name,status::text,version FROM course_sections WHERE academic_period_id=:period ORDER BY nrc")
      .param("period", periodId).query((rs, n) -> new Section(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5))).list().stream().collect(java.util.stream.Collectors.toMap(Section::nrc, value -> value, (a, b) -> a, TreeMap::new)); }
  private Map<String, Profile> profiles() { return jdbc.sql("SELECT student_academic_profile_id,person_id,account_id,student_number_normalized,current_semester,academic_status::text,version FROM student_academic_profiles ORDER BY student_number_normalized")
      .query((rs, n) -> new Profile(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getInt(5), rs.getString(6), rs.getInt(7))).list().stream().collect(java.util.stream.Collectors.toMap(Profile::number, value -> value, (a, b) -> a, TreeMap::new)); }
  private Person person(UUID id) { return jdbc.sql("SELECT source_version FROM person_status_projection WHERE person_id=:id").param("id", id).query((rs, n) -> new Person(rs.getInt(1))).optional().orElse(null); }
  private Account accountFor(UUID personId) { List<Account> accounts = jdbc.sql("SELECT account_id,login_type,status,source_version,last_deactivation_reason_code FROM account_status_projection WHERE person_id=:person AND login_type='MATRICULA' ORDER BY account_id").param("person", personId).query((rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5))).list(); if (accounts.size() > 1) throw new IdentityConflict("Resolved Person has more than one student Account"); return accounts.isEmpty() ? null : accounts.getFirst(); }
  private Account account(UUID id) { return jdbc.sql("SELECT account_id,login_type,status,source_version,last_deactivation_reason_code FROM account_status_projection WHERE account_id=:id").param("id", id).query((rs, n) -> new Account(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5))).optional().orElse(null); }
  private Role role(UUID accountId) { return jdbc.sql("SELECT status,source_version FROM authorization_context_projection WHERE account_id=:id AND role_code='ALUMNO'").param("id", accountId).query((rs, n) -> new Role(rs.getString(1), rs.getInt(2))).optional().orElse(null); }
  private Access access(UUID accountId) { return jdbc.sql("SELECT status,source_version FROM system_access_context_projection WHERE account_id=:id AND system_code='LAVADO_ULTRASONICO'").param("id", accountId).query((rs, n) -> new Access(rs.getString(1), rs.getInt(2))).optional().orElse(null); }
  private Map<String, UUID> sectionIds(UUID importId, Map<String, Nrc> desired, Ids generatedIds) { Map<String, UUID> ids = new HashMap<>(); for (String nrc : desired.keySet()) { Section section = sectionsForImport(importId, nrc); ids.put(nrc, section == null ? generatedIds.id("course-section", nrc) : section.id); } return ids; }
  private Section sectionsForImport(UUID importId, String nrc) { return jdbc.sql("SELECT course_section_id,nrc,name,status::text,version FROM course_sections WHERE academic_period_id=(SELECT academic_period_id FROM student_import_processes WHERE import_id=:importId) AND nrc=:nrc").param("importId", importId).param("nrc", nrc).query((rs, n) -> new Section(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4), rs.getInt(5))).optional().orElse(null); }
  private Map<String, Enrollment> enrollments(UUID periodId) { return jdbc.sql("""
      SELECT e.enrollment_id,e.student_academic_profile_id,e.course_section_id,p.student_number_normalized,c.nrc,e.status::text,e.version
      FROM student_course_sections e JOIN student_academic_profiles p ON p.student_academic_profile_id=e.student_academic_profile_id
      JOIN course_sections c ON c.course_section_id=e.course_section_id WHERE c.academic_period_id=:period ORDER BY p.student_number_normalized,c.nrc
      """).param("period", periodId).query((rs, n) -> new Enrollment(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class), rs.getString(4), rs.getString(5), rs.getString(6), rs.getInt(7))).list().stream().collect(java.util.stream.Collectors.toMap(value -> value.studentNumber + "\u0000" + value.nrc, value -> value, (a, b) -> a, TreeMap::new)); }
  private List<EnrollmentRow> enrollmentRows(UUID importId) { return jdbc.sql("SELECT sequence,row_data::text FROM student_import_validation_rows WHERE import_id=:importId AND row_type='ENROLLMENT' ORDER BY sequence").param("importId", importId).query((rs, n) -> { JsonNode row = codec.node(rs.getString(2)); return new EnrollmentRow(rs.getInt(1), StudentAcademicProfile.normalizedStudentNumber(text(row, "studentNumber")), text(row, "nrc")); }).list(); }

  private void insert(UUID importId, Plan plan, Instant now) { jdbc.sql("""
      INSERT INTO student_import_plan_items (import_id,plan_item_id,item_type,business_key,planned_effect,expected_versions,generated_entity_id,created_at,updated_at)
      VALUES (:importId,:id,CAST(:type AS student_import_plan_item_type),CAST(:business AS jsonb),CAST(:effect AS jsonb),CAST(:versions AS jsonb),:generated,:now,:now)
      ON CONFLICT (import_id,plan_item_id) DO NOTHING
        """).param("importId", importId).param("id", plan.id).param("type", plan.type).param("business", codec.json(plan.businessKey)).param("effect", codec.json(plan.effect)).param("versions", codec.json(plan.expectedVersions)).param("generated", plan.generatedEntityId).param("now", java.sql.Timestamp.from(now)).update(); }
  private static Summary summary(List<Plan> plans) {
    int studentsCreated = 0, studentsUpdated = 0, studentsReactivated = 0, studentsDeactivated = 0;
    int courseSectionsCreated = 0, courseSectionsActivated = 0, courseSectionsDeactivated = 0;
    int enrollmentsRegistered = 0, enrollmentsReactivated = 0, enrollmentsRemoved = 0;
    for (Plan plan : plans) switch (plan.type) {
      case "CREATE_STUDENT_PROFILE" -> studentsCreated++;
      case "UPDATE_PERSON" -> studentsUpdated++;
      case "ACTIVATE_STUDENT_PROFILE" -> studentsReactivated++;
      case "DEACTIVATE_STUDENT_PROFILE" -> studentsDeactivated++;
      case "REGISTER_COURSE_SECTION" -> courseSectionsCreated++;
      case "ACTIVATE_COURSE_SECTION" -> courseSectionsActivated++;
      case "DEACTIVATE_COURSE_SECTION" -> courseSectionsDeactivated++;
      case "ADD_STUDENT_ENROLLMENT" -> enrollmentsRegistered++;
      case "REACTIVATE_STUDENT_ENROLLMENT" -> enrollmentsReactivated++;
      case "REMOVE_STUDENT_ENROLLMENT" -> enrollmentsRemoved++;
      default -> { }
    }
    return new Summary(studentsCreated, studentsUpdated, studentsReactivated, studentsDeactivated,
        courseSectionsCreated, courseSectionsActivated, courseSectionsDeactivated, enrollmentsRegistered,
        enrollmentsReactivated, enrollmentsRemoved);
  }
  private Plan plan(UUID importId, String type, Map<String, Object> businessKey, Map<String, Object> effect, Map<String, Object> versions, UUID generated) { return new Plan(UUID.randomUUID(), type, businessKey, effect, versions, generated); }
  private static Map<String, Object> key(Object... entries) { Map<String, Object> values = new LinkedHashMap<>(); for (int i = 0; i < entries.length; i += 2) values.put((String) entries[i], entries[i + 1]); return values; }
  private static Map<String, Object> versions(String key, int version) { return Map.of(key, version); }
  private static String text(JsonNode value, String field) { return value.hasNonNull(field) ? value.path(field).asString() : null; }
  private static UUID uuid(JsonNode value, String field) { try { return value.hasNonNull(field) ? UUID.fromString(value.path(field).asString()) : null; } catch (IllegalArgumentException ex) { return null; } }

  private record Process(UUID importId, UUID periodId, int periodVersion, int version, String status) {}
  private record Resolution(String outcome, UUID personId, String status) {}
  private record ResolutionWork(String email, String result) {}
  private record Student(int sequence, String number, String firstName, String paternalSurname, String maternalSurname, String email, int semester) {}
  private record Nrc(String nrc, String name) {}
  private record Section(UUID id, String nrc, String name, String status, int version) {}
  private record Profile(UUID id, UUID personId, UUID accountId, String number, int semester, String status, int version) {}
  private record Person(int version) {}
  private record Account(UUID id, String loginType, String status, int version, String deactivationReasonCode) {}
  private record Role(String status, int version) {}
  private record Access(String status, int version) {}
  private record Enrollment(UUID id, UUID profileId, UUID sectionId, String studentNumber, String nrc, String status, int version) {}
  private record EnrollmentRow(int sequence, String studentNumber, String nrc) {}
  private record Plan(UUID id, String type, Map<String, Object> businessKey, Map<String, Object> effect, Map<String, Object> expectedVersions, UUID generatedEntityId) {}
  private record Summary(int studentsCreated, int studentsUpdated, int studentsReactivated, int studentsDeactivated,
                         int courseSectionsCreated, int courseSectionsActivated, int courseSectionsDeactivated,
                         int enrollmentsRegistered, int enrollmentsReactivated, int enrollmentsRemoved) {}

  private static final class IdentityConflict extends RuntimeException {
    IdentityConflict(String message) { super(message); }
  }

  private static final class Ids {
    private final Map<String, UUID> values = new HashMap<>();
    UUID id(String type, String key) { return values.computeIfAbsent(type + "\u0000" + key, ignored -> UUID.randomUUID()); }
  }
}
