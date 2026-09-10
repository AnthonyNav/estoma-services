package com.estoma.washexecution.infrastructure.messaging.recovery;

import com.estoma.washexecution.application.command.ActorContext;
import com.estoma.washexecution.application.port.JsonCodec;
import com.estoma.washexecution.application.port.OutboxRepository;
import com.estoma.washexecution.application.port.ProjectionRecoveryRepository;
import com.estoma.washexecution.infrastructure.messaging.MessagingProperties;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public final class ProjectionRecoveryCoordinator {
  private static final String PRODUCER="wash-execution";
  private final RecoveryJobStore jobs; private final ProjectionRecoveryRepository state; private final OutboxRepository outbox;
  private final JsonCodec json; private final JdbcTemplate jdbc; private final MessagingProperties properties; private final TransactionTemplate transactions;
  public ProjectionRecoveryCoordinator(RecoveryJobStore jobs,ProjectionRecoveryRepository state,OutboxRepository outbox,JsonCodec json,JdbcTemplate jdbc,MessagingProperties properties,PlatformTransactionManager manager){this.jobs=jobs;this.state=state;this.outbox=outbox;this.json=json;this.jdbc=jdbc;this.properties=properties;this.transactions=new TransactionTemplate(manager);}

  public void ensureBootstrap(LocalDate serviceDate){
    transactions.executeWithoutResult(s->{
      ensureBootstrapProjection("wash-administration",null);
      ensureBootstrapProjection("authorization",null);
      if(!jobs.hasCompletedFull("appointments",serviceDate)&&jobs.findActiveFull("appointments",serviceDate).isEmpty()) startFullLocked("appointments",serviceDate);
      recoverMissingAppointmentSlots();
    });
  }
  private void recoverMissingAppointmentSlots() {
    // Daily snapshots contain booked contexts only. A later scheduling event can therefore
    // reference a slot removed by the rebuild, even when its inbox record is already APPLIED.
    var missing = jdbc.queryForList("""
        SELECT a.appointment_id FROM appointment_projection a
        WHERE a.appointment_status='SCHEDULED'
          AND NOT EXISTS (SELECT 1 FROM appointment_time_slot_projection s
            WHERE s.appointment_time_slot_id=a.appointment_time_slot_id)
          AND NOT EXISTS (SELECT 1 FROM projection_recovery_jobs f
            WHERE f.projection_name='appointments' AND f.scope='FULL'
              AND f.status IN ('PENDING','WAITING_RESULT'))
          AND NOT EXISTS (SELECT 1 FROM projection_recovery_jobs e
            WHERE e.projection_name='appointments' AND e.scope='ENTITY'
              AND e.entity_key='appointment:' || a.appointment_id::text
              AND (e.status IN ('PENDING','WAITING_RESULT') OR e.updated_at>=a.updated_at))
        ORDER BY a.updated_at,a.appointment_id
        LIMIT 10
        FOR UPDATE OF a SKIP LOCKED
        """, UUID.class);
    for (UUID appointmentId : missing) ensureAppointmentEntity(appointmentId);
  }
  private void ensureBootstrapProjection(String projection,LocalDate serviceDate){
    var current=state.find(projection);
    boolean recoveryRequired=current.isEmpty()||"DEGRADED".equals(current.get().status());
    if(recoveryRequired&&jobs.findActiveFull(projection,serviceDate).isEmpty())startFullLocked(projection,serviceDate);
  }
  public void ensureFull(String projection,LocalDate serviceDate){transactions.executeWithoutResult(s->{if(jobs.findActiveFull(projection,serviceDate).isEmpty())startFullLocked(projection,serviceDate);});}
  public void ensureAppointmentEntity(UUID appointmentId){if(appointmentId==null)return;transactions.executeWithoutResult(s->{String key="appointment:"+appointmentId;if(jobs.findActiveEntity("appointments",key).isEmpty())jobs.createEntity("appointments",key,Instant.now());});}

  public void publishPendingQueries(){
    Instant now=Instant.now(); jobs.requeueTimedOut(now.minus(properties.recoveryResultTimeout()),now);
    UUID claim=UUID.randomUUID(); var claimed=transactions.execute(s->jobs.claimPending(10,now,claim,now.plus(properties.outboxLease())));
    if(claimed==null)return;
    for(var job:claimed) transactions.executeWithoutResult(s->publish(job,claim));
  }

  private void startFullLocked(String projection,LocalDate date){
    state.mark(projection,projection,"REBUILDING",null,Instant.now()); clearProjection(projection,date); jobs.createFull(projection,date,Instant.now());
  }
  private void clearProjection(String projection,LocalDate date){
    switch(projection){
      case "appointments"->{if(date==null)throw new IllegalArgumentException("SERVICE_DATE_REQUIRED");jdbc.update("DELETE FROM appointment_projection WHERE appointment_time_slot_id IN (SELECT appointment_time_slot_id FROM appointment_time_slot_projection WHERE service_date=?)",date);jdbc.update("DELETE FROM appointment_time_slot_projection WHERE service_date=?",date);}
      case "wash-administration"->jdbc.execute("TRUNCATE TABLE administrative_closure_projection,resource_priority_projection,tank_projection,cabin_projection");
      case "authorization"->jdbc.execute("TRUNCATE TABLE authorization_access_projection,authorization_role_projection");
      default->throw new IllegalArgumentException("UNSUPPORTED_PROJECTION:"+projection);
    }
  }
  private void publish(RecoveryJobStore.Job job,UUID claim){
    String type; String owner=job.projectionName(); Map<String,Object> payload=new LinkedHashMap<>();
    if(job.projectionName().equals("appointments")&&job.scope()==RecoveryJobStore.Scope.FULL){type="ListAppointmentExecutionContextsPage";payload.put("serviceDate",job.serviceDate().toString());payload.put("pageSize",200);if(job.cursor()!=null)payload.put("pageToken",job.cursor());}
    else if(job.projectionName().equals("appointments")){type="GetAppointmentExecutionContext";payload.put("appointmentId",UUID.fromString(job.entityKey().substring("appointment:".length())));}
    else if(job.projectionName().equals("wash-administration")){type="GetWashAdministrationConfiguration";}
    else if(job.projectionName().equals("authorization")){type="ListAccountAuthorizationSnapshotsPage";payload.put("limit",200);payload.put("systemCode","LAVADO_ULTRASONICO");if(job.cursor()!=null)payload.put("cursor",job.cursor());}
    else throw new IllegalArgumentException("UNSUPPORTED_PROJECTION:"+job.projectionName());
    UUID messageId=UUID.randomUUID(),operationId=UUID.randomUUID(),correlationId=UUID.randomUUID(); Instant now=Instant.now();
    outbox.append(new OutboxRepository.OutboxMessage(messageId,type,1,"QUERY",PRODUCER,owner,now,operationId,null,null,null,correlationId,null,json.write(ActorContext.system(PRODUCER)),json.write(payload)));
    if(!jobs.markWaiting(job.jobId(),claim,type,messageId,operationId,correlationId,now))throw new IllegalStateException("RECOVERY_JOB_FENCE_LOST");
  }
}
