package com.estoma.appointments.application.recovery;

import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import com.estoma.appointments.application.port.out.AuthorizationProjectionRepository;
import com.estoma.appointments.application.port.out.OutboxRepository;
import com.estoma.appointments.application.port.out.PendingProjectionEventRepository;
import com.estoma.appointments.application.port.out.ProcessedMessageRepository;
import com.estoma.appointments.application.port.out.ProjectionRecoveryJobRepository;
import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import com.estoma.appointments.application.port.out.WashExecutionOperationalProjectionRepository;
import com.estoma.appointments.contracts.MessageEnvelope;
import com.estoma.appointments.infrastructure.messaging.PendingProjectionRecoveryWorker;
import com.estoma.appointments.infrastructure.messaging.ProjectionRecoveryWorker;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
public class ProjectionRecoveryResultProcessor {
  private final ProjectionRecoveryCoordinator recovery;
  private final ProjectionRecoveryJobRepository jobs;
  private final ProcessedMessageRepository inbox;
  private final AcademicProjectionRepository academic;
  private final AuthorizationProjectionRepository authorization;
  private final WashAdministrationProjectionRepository administration;
  private final WashExecutionOperationalProjectionRepository execution;
  private final WashExecutionSnapshotReconciler executionReconciler;
  private final PendingProjectionRecoveryWorker pendingWorker;
  private final PendingProjectionEventRepository pending;
  private final OutboxRepository outbox;
  private final Clock clock;

  public ProjectionRecoveryResultProcessor(ProjectionRecoveryCoordinator recovery,
      ProjectionRecoveryJobRepository jobs, ProcessedMessageRepository inbox,
      AcademicProjectionRepository academic, AuthorizationProjectionRepository authorization,
      WashAdministrationProjectionRepository administration,
      WashExecutionOperationalProjectionRepository execution,
      WashExecutionSnapshotReconciler executionReconciler,
      PendingProjectionRecoveryWorker pendingWorker, PendingProjectionEventRepository pending,
      OutboxRepository outbox, Clock clock) {
    this.recovery=recovery; this.jobs=jobs; this.inbox=inbox; this.academic=academic;
    this.authorization=authorization; this.administration=administration; this.execution=execution;
    this.executionReconciler=executionReconciler; this.pendingWorker=pendingWorker;
    this.pending=pending; this.outbox=outbox; this.clock=clock;
  }

  @Transactional
  public void process(MessageEnvelope result) {
    Instant now=clock.instant();
    if(!inbox.tryClaim(result.messageId(),result.messageType(),now)) return;
    String projection=projection(result.messageType());
    var job=jobs.find(projection).orElse(null);
    if(job==null || result.causationId()==null || !result.causationId().equals(job.currentQueryMessageId())){
      inbox.markApplied(result.messageId(),now); // stale/late Result from a previous recovery attempt
      return;
    }
    String status=text(result.payload(),"status");
    if("REJECTED".equals(status)||"FAILED".equals(status)||"EXPIRED".equals(status)){
      String code=optionalText(result.payload().path("error"),"code");
      recovery.degraded(projection,"recovery Query returned "+status+(code==null?"":": "+code));
      jobs.delete(projection);
      inbox.markApplied(result.messageId(),now);
      return;
    }

    switch(result.messageType()){
      case "GetAppointmentAcademicConfigurationResult" -> applyAcademicConfiguration(result,job,now);
      case "ListStudentAppointmentAcademicSnapshotsPageResult" -> applyAcademicPage(result,job,now);
      case "ListAccountAuthorizationSnapshotsPageResult" -> applyAuthorizationPage(result,job,now);
      case "GetWashAdministrationConfigurationResult" -> applyAdministration(result,job,now);
      case "ListWashExecutionIntegrationContextsPageResult" -> applyExecutionPage(result,job,now);
      default -> throw new IllegalArgumentException("unsupported recovery Result "+result.messageType());
    }
    inbox.markApplied(result.messageId(),now);
  }

  private void applyAcademicConfiguration(MessageEnvelope result,
      ProjectionRecoveryJobRepository.RecoveryJob job,Instant now){
    requireStatus(result,"SUCCEEDED");
    JsonNode data=object(result.payload(),"data");
    long maxVersion=0;
    JsonNode period=data.path("activePeriod");
    UUID periodId=null;
    if(!period.isMissingNode()&&!period.isNull()){
      periodId=uuid(period,"academicPeriodId"); long version=positiveLong(period,"version");
      academic.upsertAcademicPeriod(new AcademicProjectionRepository.AcademicPeriodProjection(
          periodId,text(period,"code"),text(period,"status"),version,result.occurredAt(),true));
      maxVersion=Math.max(maxVersion,version);
    }
    JsonNode sections=array(data,"courseSections");
    for(JsonNode section:sections){
      long version=positiveLong(section,"version");
      academic.upsertCourseSection(new AcademicProjectionRepository.CourseSectionProjection(
          uuid(section,"courseSectionId"),uuid(section,"academicPeriodId"),text(section,"status"),
          version,result.occurredAt(),true));
      maxVersion=Math.max(maxVersion,version);
    }
    if(periodId==null){ finish("academic",job,maxVersion,now); return; }
    UUID next=UUID.randomUUID();
    String context="{\"academicPeriodId\":\""+periodId+"\"}";
    if(!jobs.advance("academic",job.generationId(),job.currentQueryMessageId(),"PAGE",next,null,context,now))
      throw new IllegalStateException("Academic recovery job changed concurrently");
    appendQuery(next,job.generationId(),"ListStudentAppointmentAcademicSnapshotsPage",
        ProjectionRecoveryWorker.pagePayload("academic",null,context),now);
  }

  private void applyAcademicPage(MessageEnvelope result,
      ProjectionRecoveryJobRepository.RecoveryJob job,Instant now){
    requireStatus(result,"SUCCEEDED"); JsonNode data=object(result.payload(),"data"); long max=0;
    UUID expectedPeriod=UUID.fromString(contextString(job.contextJson(),"academicPeriodId"));
    if(!uuid(data,"academicPeriodId").equals(expectedPeriod)) throw new IllegalArgumentException("Academic recovery period mismatch");
    for(JsonNode item:array(data,"items")){
      long pv=positiveLong(item,"profileVersion");
      UUID profile=uuid(item,"studentAcademicProfileId"),account=uuid(item,"accountId");
      academic.upsertStudentProfile(new AcademicProjectionRepository.StudentAcademicProfileProjection(
          profile,account,intValue(item,"currentSemester"),text(item,"academicStatus"),pv,result.occurredAt(),true));
      max=Math.max(max,pv);
      for(JsonNode enrollment:array(item,"enrollments")){
        long ev=positiveLong(enrollment,"enrollmentVersion");
        academic.upsertStudentCourse(new AcademicProjectionRepository.StudentCourseSectionProjection(
            profile,account,uuid(enrollment,"courseSectionId"),uuid(enrollment,"academicPeriodId"),
            instant(enrollment,"enrolledAt"),optionalInstant(enrollment,"removedAt"),ev,result.occurredAt(),true));
        max=Math.max(max,ev);
      }
    }
    boolean more=bool(data,"hasMore"); String cursor=optionalText(data,"nextCursor");
    if(more){
      if(cursor==null) throw new IllegalArgumentException("Academic recovery hasMore requires nextCursor");
      UUID next=UUID.randomUUID();
      if(!jobs.advance("academic",job.generationId(),job.currentQueryMessageId(),"PAGE",next,cursor,job.contextJson(),now))
        throw new IllegalStateException("Academic recovery job changed concurrently");
      appendQuery(next,job.generationId(),"ListStudentAppointmentAcademicSnapshotsPage",
          ProjectionRecoveryWorker.pagePayload("academic",cursor,job.contextJson()),now);
    }else finish("academic",job,max,now);
  }

  private void applyAuthorizationPage(MessageEnvelope result,
      ProjectionRecoveryJobRepository.RecoveryJob job,Instant now){
    requireStatus(result,"SUCCEEDED"); JsonNode data=object(result.payload(),"data"); long max=0;
    for(JsonNode item:array(data,"items")){
      UUID account=uuid(item,"accountId"); JsonNode role=item.path("role");
      if(!role.isMissingNode()&&!role.isNull()){
        long rv=positiveLong(role,"roleVersion");
        authorization.upsertAccountRole(new AuthorizationProjectionRepository.AccountRoleProjection(
            account,null,text(role,"roleCode"),rv,result.occurredAt(),true)); max=Math.max(max,rv);
      }
      for(JsonNode access:array(item,"systemAccesses")){
        long av=positiveLong(access,"accessVersion");
        authorization.upsertSystemAccess(new AuthorizationProjectionRepository.SystemAccessProjection(
            uuid(access,"systemAccessId"),account,uuid(access,"systemId"),text(access,"systemCode"),
            text(access,"status"),av,result.occurredAt(),true)); max=Math.max(max,av);
      }
    }
    boolean more=bool(data,"hasMore"); String cursor=optionalText(data,"nextCursor");
    if(more){
      if(cursor==null) throw new IllegalArgumentException("Authorization recovery hasMore requires nextCursor");
      UUID next=UUID.randomUUID();
      if(!jobs.advance("authorization",job.generationId(),job.currentQueryMessageId(),"PAGE",next,cursor,job.contextJson(),now))
        throw new IllegalStateException("Authorization recovery job changed concurrently");
      appendQuery(next,job.generationId(),"ListAccountAuthorizationSnapshotsPage",
          ProjectionRecoveryWorker.pagePayload("authorization",cursor,job.contextJson()),now);
    }else finish("authorization",job,max,now);
  }

  private void applyAdministration(MessageEnvelope result,
      ProjectionRecoveryJobRepository.RecoveryJob job,Instant now){
    requireStatus(result,"SUCCEEDED"); JsonNode data=object(result.payload(),"data"); long max=0;
    for(JsonNode p:array(data,"serviceCalendars")){ long v=positiveLong(p,"version");
      administration.upsertServiceCalendar(new WashAdministrationProjectionRepository.ServiceCalendarProjection(
          uuid(p,"calendarId"),text(p,"code"),text(p,"timezone"),LocalDate.parse(text(p,"effectiveFrom")),
          optionalDate(p,"effectiveUntil"),text(p,"status"),v,result.occurredAt())); max=Math.max(max,v); }
    for(JsonNode p:array(data,"serviceSchedules")){ long v=positiveLong(p,"version");
      administration.upsertServiceSchedule(new WashAdministrationProjectionRepository.ServiceScheduleProjection(
          uuid(p,"scheduleId"),uuid(p,"calendarId"),intValue(p,"dayOfWeek"),LocalTime.parse(text(p,"openingTime")),
          LocalTime.parse(text(p,"closingTime")),intValue(p,"slotDurationMinutes"),text(p,"status"),v,result.occurredAt())); max=Math.max(max,v); }
    for(JsonNode p:array(data,"administrativeClosures")){ long v=positiveLong(p,"version");
      administration.upsertAdministrativeClosure(new WashAdministrationProjectionRepository.AdministrativeClosureProjection(
          uuid(p,"closureId"),uuid(p,"calendarId"),text(p,"scope"),optionalUuid(p,"cabinId"),optionalUuid(p,"tankId"),
          instant(p,"startsAt"),instant(p,"endsAt"),text(p,"status"),v,result.occurredAt())); max=Math.max(max,v); }
    for(JsonNode p:array(data,"cabins")){ long v=positiveLong(p,"version");
      administration.upsertCabin(new WashAdministrationProjectionRepository.CabinProjection(
          uuid(p,"cabinId"),text(p,"cabinType"),text(p,"status"),v,result.occurredAt())); max=Math.max(max,v); }
    for(JsonNode p:array(data,"tanks")){ long v=positiveLong(p,"version");
      administration.upsertTank(new WashAdministrationProjectionRepository.TankProjection(
          uuid(p,"tankId"),uuid(p,"cabinId"),intValue(p,"configuredCapacity"),text(p,"status"),v,result.occurredAt())); max=Math.max(max,v); }
    for(JsonNode p:array(data,"resourcePriorities")){ long v=positiveLong(p,"version");
      administration.upsertResourcePriority(new WashAdministrationProjectionRepository.ResourcePriorityProjection(
          uuid(p,"resourcePriorityId"),text(p,"appointmentType"),text(p,"cabinType"),text(p,"usageMode"),
          intValue(p,"priorityOrder"),text(p,"status"),v,result.occurredAt())); max=Math.max(max,v); }
    finish("wash-administration",job,max,now);
  }

  private void applyExecutionPage(MessageEnvelope result,
      ProjectionRecoveryJobRepository.RecoveryJob job,Instant now){
    requireStatus(result,"SUCCEEDED"); JsonNode data=object(result.payload(),"data");
    Instant generated=instant(data,"snapshotGeneratedAt"); long max=0;
    String expectedDate=contextString(job.contextJson(),"serviceDate");
    if(!expectedDate.equals(text(data,"serviceDate"))) throw new IllegalArgumentException("Execution recovery serviceDate mismatch");
    for(JsonNode x:array(data,"executions")){
      long v=positiveLong(x,"version"); max=Math.max(max,v);
      executionReconciler.reconcile(new WashExecutionSnapshotReconciler.ExecutionSnapshot(
          uuid(x,"washExecutionId"),uuid(x,"appointmentId"),text(x,"status"),v,
          optionalInstant(x,"arrivedAt"),optionalInstant(x,"startedAt"),optionalInstant(x,"entryAuthorizedAt"),
          optionalInstant(x,"rejectedAt"),optionalText(x,"rejectionReason"),optionalUuid(x,"rejectedByAccountId"),
          optionalInstant(x,"completedAt"),optionalUuid(x,"completedByAccountId"),optionalInstant(x,"cancelledAt"),
          optionalText(x,"cancellationType"),optionalText(x,"cancellationSubreason"),optionalText(x,"cancellationReason"),
          optionalUuid(x,"cancelledByAccountId")),generated);
    }
    for(JsonNode x:array(data,"resourceUnavailabilities")){ long v=positiveLong(x,"version"); max=Math.max(max,v);
      execution.upsertResourceUnavailability(new WashExecutionOperationalProjectionRepository.ResourceUnavailabilityProjection(
          uuid(x,"resourceUnavailabilityId"),optionalUuid(x,"cabinId"),optionalUuid(x,"tankId"),instant(x,"disabledAt"),
          optionalInstant(x,"restoredAt"),v,generated)); }
    for(JsonNode x:array(data,"cleaningPeriods")){ long v=positiveLong(x,"version"); max=Math.max(max,v);
      execution.upsertCleaningPeriod(new WashExecutionOperationalProjectionRepository.CleaningPeriodProjection(
          uuid(x,"cleaningPeriodId"),uuid(x,"washExecutionId"),uuid(x,"resourceAssignmentId"),uuid(x,"cabinId"),
          text(x,"cleaningTrigger"),instant(x,"startsAt"),instant(x,"expectedEndsAt"),optionalInstant(x,"completedAt"),v,generated)); }
    String cursor=optionalText(data,"nextPageToken");
    if(cursor!=null){ UUID next=UUID.randomUUID();
      if(!jobs.advance("wash-execution",job.generationId(),job.currentQueryMessageId(),"PAGE",next,cursor,job.contextJson(),now))
        throw new IllegalStateException("Execution recovery job changed concurrently");
      appendQuery(next,job.generationId(),"ListWashExecutionIntegrationContextsPage",
          ProjectionRecoveryWorker.pagePayload("wash-execution",cursor,job.contextJson()),now);
    }else finish("wash-execution",job,max,now);
  }

  private void finish(String projection,ProjectionRecoveryJobRepository.RecoveryJob job,long sourceVersion,Instant now){
    pendingWorker.replay(projection);
    if(pending.hasOpen(projection)){
      recovery.degraded(projection,"deferred projection Events remain unresolved after authoritative snapshot");
      jobs.delete(projection); return;
    }
    if(!jobs.complete(projection,job.generationId(),job.currentQueryMessageId()))
      throw new IllegalStateException("recovery job changed before completion");
    recovery.ready(projection,sourceVersion);
  }

  private void appendQuery(UUID id,UUID correlation,String type,String payload,Instant now){
    outbox.append(new OutboxRepository.OutboxMessage(id,type,1,"QUERY",null,null,correlation,null,payload,
        now,now,"PENDING",0,null,null));
  }
  private static String projection(String type){ return switch(type){
    case "GetAppointmentAcademicConfigurationResult","ListStudentAppointmentAcademicSnapshotsPageResult" -> "academic";
    case "ListAccountAuthorizationSnapshotsPageResult" -> "authorization";
    case "GetWashAdministrationConfigurationResult" -> "wash-administration";
    case "ListWashExecutionIntegrationContextsPageResult" -> "wash-execution";
    default -> throw new IllegalArgumentException("unknown recovery result "+type); }; }
  private static void requireStatus(MessageEnvelope result,String expected){ String actual=text(result.payload(),"status"); if(!expected.equals(actual)) throw new IllegalArgumentException("expected recovery status "+expected+" but got "+actual); }
  private static JsonNode object(JsonNode n,String f){ JsonNode v=n.path(f); if(!v.isObject()) throw new IllegalArgumentException(f+" must be object"); return v; }
  private static JsonNode array(JsonNode n,String f){ JsonNode v=n.path(f); if(!v.isArray()) throw new IllegalArgumentException(f+" must be array"); return v; }
  private static String text(JsonNode n,String f){ String v=optionalText(n,f); if(v==null||v.isBlank()) throw new IllegalArgumentException(f+" is required"); return v; }
  private static String optionalText(JsonNode n,String f){ return !n.has(f)||n.path(f).isNull()?null:n.path(f).asString(); }
  private static UUID uuid(JsonNode n,String f){ UUID v=optionalUuid(n,f); if(v==null) throw new IllegalArgumentException(f+" is required"); return v; }
  private static UUID optionalUuid(JsonNode n,String f){ String v=optionalText(n,f); if(v==null)return null; try{return UUID.fromString(v);}catch(Exception e){throw new IllegalArgumentException(f+" must be UUID",e);} }
  private static long positiveLong(JsonNode n,String f){ long v=n.path(f).asLong(-1); if(v<=0)throw new IllegalArgumentException(f+" must be positive"); return v; }
  private static int intValue(JsonNode n,String f){ if(!n.has(f)||!n.path(f).canConvertToInt())throw new IllegalArgumentException(f+" must be integer"); return n.path(f).asInt(); }
  private static boolean bool(JsonNode n,String f){ if(!n.has(f)||!n.path(f).isBoolean())throw new IllegalArgumentException(f+" must be boolean"); return n.path(f).asBoolean(); }
  private static Instant instant(JsonNode n,String f){ Instant v=optionalInstant(n,f); if(v==null)throw new IllegalArgumentException(f+" is required"); return v; }
  private static Instant optionalInstant(JsonNode n,String f){ String v=optionalText(n,f); if(v==null)return null; return Instant.parse(v); }
  private static LocalDate optionalDate(JsonNode n,String f){ String v=optionalText(n,f); return v==null?null:LocalDate.parse(v); }
  private static String contextString(String json,String field){ String m="\""+field+"\":\""; int s=json.indexOf(m); if(s<0)throw new IllegalArgumentException("missing context "+field); s+=m.length(); int e=json.indexOf('"',s); if(e<0)throw new IllegalArgumentException("invalid context"); return json.substring(s,e); }
}
