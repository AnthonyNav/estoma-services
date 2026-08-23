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
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/** Reads persisted Academic snapshots and emits correlated durable Results. */
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

  @Transactional
  public void dispatch(MessageEnvelope query) {
    switch (query.messageType()) {
      case "GetStudentAcademicEligibilitySnapshot" -> getEligibility(query);
      case "ListStudentAcademicEligibilitySnapshotsPage" -> listEligibility(query);
      case "GetAppointmentAcademicConfiguration" -> getAppointmentConfiguration(query);
      case "ListStudentAppointmentAcademicSnapshotsPage" -> listAppointmentStudents(query);
      default -> throw new IllegalArgumentException("unsupported Academic Query");
    }
  }

  @Transactional public void reject(MessageEnvelope query, String errorCode) { appendTerminalResult(query,"REJECTED",errorCode); }
  @Transactional public void fail(MessageEnvelope query, String errorCode) { appendTerminalResult(query,"FAILED",errorCode); }

  private void appendTerminalResult(MessageEnvelope query, String status, String errorCode) {
    Map<String,Object> payload = new LinkedHashMap<>();
    payload.put("status",status);
    payload.put("error",Map.of("code",errorCode));
    appendResult(query,query.messageType()+"Result",null,null,payload);
  }

  private void getEligibility(MessageEnvelope query) {
    JsonNode payload=query.payload();
    UUID profileId=uuid(payload,"studentAcademicProfileId");
    UUID accountId=uuid(payload,"accountId");
    Eligibility eligibility=profileId!=null?findByProfile(profileId):findByAccount(accountId);
    Map<String,Object> result=new LinkedHashMap<>();
    result.put("status",eligibility==null?"NOT_FOUND":"FOUND");
    result.put("data",eligibility==null?null:eligibility.data());
    appendResult(query,"GetStudentAcademicEligibilitySnapshotResult",
        eligibility==null?null:eligibility.studentAcademicProfileId(),
        eligibility==null?null:eligibility.eligibilityVersion(),result);
  }

  private void listEligibility(MessageEnvelope query) {
    JsonNode payload=query.payload();
    UUID cursor=uuid(payload,"cursor");
    int limit=payload.path("limit").asInt();
    List<Eligibility> rows=jdbc.sql("""
        SELECT e.eligibility_id,e.student_academic_profile_id,p.account_id,e.system_code,
               e.eligible,e.policy_version,e.eligibility_version,e.academic_profile_version,
               e.evaluated_at,e.updated_at
        FROM student_academic_eligibilities e
        JOIN student_academic_profiles p ON p.student_academic_profile_id=e.student_academic_profile_id
        WHERE e.system_code=:system AND (:cursor IS NULL OR e.eligibility_id>:cursor)
        ORDER BY e.eligibility_id LIMIT :limit
        """)
        .param("system",SYSTEM_CODE).param("cursor",cursor).param("limit",limit+1)
        .query((rs,n)->new Eligibility(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),
            rs.getObject(3,UUID.class),rs.getString(4),rs.getBoolean(5),rs.getInt(6),rs.getInt(7),
            rs.getInt(8),rs.getTimestamp(9).toInstant(),rs.getTimestamp(10).toInstant())).list();
    boolean hasMore=rows.size()>limit;
    if(hasMore) rows=new ArrayList<>(rows.subList(0,limit));
    String nextCursor=hasMore&&!rows.isEmpty()?rows.get(rows.size()-1).eligibilityId().toString():null;
    Map<String,Object> data=new LinkedHashMap<>();
    data.put("items",rows.stream().map(Eligibility::data).toList()); data.put("nextCursor",nextCursor); data.put("hasMore",hasMore);
    appendResult(query,"ListStudentAcademicEligibilitySnapshotsPageResult",null,null,Map.of("status","SUCCEEDED","data",data));
  }

  private void getAppointmentConfiguration(MessageEnvelope query) {
    Period active=jdbc.sql("SELECT academic_period_id,code,status,version FROM academic_periods WHERE status='ACTIVE' LIMIT 1")
        .query((rs,n)->new Period(rs.getObject(1,UUID.class),rs.getString(2),rs.getString(3),rs.getInt(4))).optional().orElse(null);
    List<Map<String,Object>> sections=active==null?List.of():jdbc.sql("""
        SELECT course_section_id,academic_period_id,status,version FROM course_sections
        WHERE academic_period_id=:period ORDER BY course_section_id
        """).param("period",active.academicPeriodId()).query((rs,n)->{
          Map<String,Object> item=new LinkedHashMap<>();
          item.put("courseSectionId",rs.getObject(1,UUID.class)); item.put("academicPeriodId",rs.getObject(2,UUID.class));
          item.put("status",rs.getString(3)); item.put("version",rs.getInt(4)); return item;
        }).list();
    Map<String,Object> data=new LinkedHashMap<>();
    data.put("snapshotGeneratedAt",Instant.now()); data.put("activePeriod",active==null?null:active.data()); data.put("courseSections",sections);
    appendResult(query,"GetAppointmentAcademicConfigurationResult",active==null?null:active.academicPeriodId(),
        active==null?null:active.version(),Map.of("status","SUCCEEDED","data",data));
  }

  private void listAppointmentStudents(MessageEnvelope query) {
    JsonNode payload=query.payload(); UUID periodId=uuid(payload,"academicPeriodId"); UUID cursor=uuid(payload,"cursor"); int limit=payload.path("limit").asInt();
    if(jdbc.sql("SELECT count(*) FROM academic_periods WHERE academic_period_id=:period").param("period",periodId).query(Long.class).single()==0){
      reject(query,"INVALID_ACADEMIC_PERIOD_ID"); return;
    }
    List<StudentSnapshot> students=jdbc.sql("""
        SELECT student_academic_profile_id,account_id,current_semester,academic_status,version
        FROM student_academic_profiles WHERE (:cursor IS NULL OR student_academic_profile_id>:cursor)
        ORDER BY student_academic_profile_id LIMIT :limit
        """).param("cursor",cursor).param("limit",limit+1)
        .query((rs,n)->new StudentSnapshot(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getInt(3),rs.getString(4),rs.getInt(5))).list();
    boolean hasMore=students.size()>limit;
    if(hasMore) students=new ArrayList<>(students.subList(0,limit));
    String nextCursor=hasMore&&!students.isEmpty()?students.get(students.size()-1).studentAcademicProfileId().toString():null;
    List<Map<String,Object>> items=new ArrayList<>();
    for(StudentSnapshot student:students){
      List<Map<String,Object>> enrollments=jdbc.sql("""
          SELECT scs.course_section_id,cs.academic_period_id,scs.enrolled_at,scs.removed_at,scs.version
          FROM student_course_sections scs JOIN course_sections cs ON cs.course_section_id=scs.course_section_id
          WHERE scs.student_academic_profile_id=:profile AND cs.academic_period_id=:period ORDER BY scs.course_section_id
          """).param("profile",student.studentAcademicProfileId()).param("period",periodId).query((rs,n)->{
            Map<String,Object> enrollment=new LinkedHashMap<>();
            enrollment.put("courseSectionId",rs.getObject(1,UUID.class)); enrollment.put("academicPeriodId",rs.getObject(2,UUID.class));
            enrollment.put("enrolledAt",rs.getTimestamp(3).toInstant()); enrollment.put("removedAt",rs.getTimestamp(4)==null?null:rs.getTimestamp(4).toInstant());
            enrollment.put("enrollmentVersion",rs.getInt(5)); return enrollment;
          }).list();
      items.add(student.data(enrollments));
    }
    Map<String,Object> data=new LinkedHashMap<>();
    data.put("academicPeriodId",periodId); data.put("items",items); data.put("nextCursor",nextCursor);
    data.put("hasMore",hasMore); data.put("snapshotGeneratedAt",Instant.now());
    appendResult(query,"ListStudentAppointmentAcademicSnapshotsPageResult",null,null,Map.of("status","SUCCEEDED","data",data));
  }

  private Eligibility findByProfile(UUID profileId){return jdbc.sql("""
      SELECT e.eligibility_id,e.student_academic_profile_id,p.account_id,e.system_code,e.eligible,e.policy_version,
             e.eligibility_version,e.academic_profile_version,e.evaluated_at,e.updated_at
      FROM student_academic_eligibilities e JOIN student_academic_profiles p ON p.student_academic_profile_id=e.student_academic_profile_id
      WHERE e.student_academic_profile_id=:profile AND e.system_code=:system
      """).param("profile",profileId).param("system",SYSTEM_CODE).query((rs,n)->eligibility(rs)).optional().orElse(null);}
  private Eligibility findByAccount(UUID accountId){return jdbc.sql("""
      SELECT e.eligibility_id,e.student_academic_profile_id,p.account_id,e.system_code,e.eligible,e.policy_version,
             e.eligibility_version,e.academic_profile_version,e.evaluated_at,e.updated_at
      FROM student_academic_eligibilities e JOIN student_academic_profiles p ON p.student_academic_profile_id=e.student_academic_profile_id
      WHERE p.account_id=:account AND e.system_code=:system
      """).param("account",accountId).param("system",SYSTEM_CODE).query((rs,n)->eligibility(rs)).optional().orElse(null);}
  private static Eligibility eligibility(java.sql.ResultSet rs)throws java.sql.SQLException{return new Eligibility(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getObject(3,UUID.class),rs.getString(4),rs.getBoolean(5),rs.getInt(6),rs.getInt(7),rs.getInt(8),rs.getTimestamp(9).toInstant(),rs.getTimestamp(10).toInstant());}

  private void appendResult(MessageEnvelope query,String messageType,UUID aggregateId,Integer aggregateVersion,Map<String,Object> payload){
    Map<String,Object> metadata=new LinkedHashMap<>();
    metadata.put("destination",query.producer());
    if(query.operationId()!=null) metadata.put("operationId",query.operationId());
    if(query.actor()!=null) metadata.put("actor",query.actor());
    outbox.append(UUID.randomUUID(),messageType,"RESULT",aggregateId,aggregateVersion,query.correlationId(),query.messageId(),codec.json(payload),codec.json(metadata),Instant.now());
  }
  private static UUID uuid(JsonNode payload,String field){if(!payload.hasNonNull(field))return null;try{return UUID.fromString(payload.path(field).asString());}catch(IllegalArgumentException ex){throw new IllegalArgumentException("invalid "+field,ex);}}

  private record Period(UUID academicPeriodId,String code,String status,int version){Map<String,Object> data(){Map<String,Object>d=new LinkedHashMap<>();d.put("academicPeriodId",academicPeriodId);d.put("code",code);d.put("status",status);d.put("version",version);return d;}}
  private record StudentSnapshot(UUID studentAcademicProfileId,UUID accountId,int currentSemester,String academicStatus,int profileVersion){Map<String,Object> data(List<Map<String,Object>> enrollments){Map<String,Object>d=new LinkedHashMap<>();d.put("studentAcademicProfileId",studentAcademicProfileId);d.put("accountId",accountId);d.put("currentSemester",currentSemester);d.put("academicStatus",academicStatus);d.put("profileVersion",profileVersion);d.put("enrollments",enrollments);return d;}}
  private record Eligibility(UUID eligibilityId,UUID studentAcademicProfileId,UUID accountId,String systemCode,boolean eligible,int policyVersion,int eligibilityVersion,int academicProfileVersion,Instant evaluatedAt,Instant updatedAt){Map<String,Object> data(){Map<String,Object>d=new LinkedHashMap<>();d.put("eligibilityId",eligibilityId);d.put("studentAcademicProfileId",studentAcademicProfileId);d.put("accountId",accountId);d.put("systemCode",systemCode);d.put("eligible",eligible);d.put("policyVersion",policyVersion);d.put("eligibilityVersion",eligibilityVersion);d.put("academicProfileVersion",academicProfileVersion);d.put("evaluatedAt",evaluatedAt);d.put("updatedAt",updatedAt);return d;}}
}
