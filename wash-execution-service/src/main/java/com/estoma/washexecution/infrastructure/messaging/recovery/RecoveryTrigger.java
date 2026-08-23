package com.estoma.washexecution.infrastructure.messaging.recovery;

import com.estoma.washexecution.contracts.MessageEnvelope;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
public final class RecoveryTrigger {
  private final ProjectionRecoveryCoordinator recovery;
  public RecoveryTrigger(ProjectionRecoveryCoordinator recovery){this.recovery=recovery;}
  public void onDeferred(MessageEnvelope e){
    if("appointments".equals(e.producer())){
      UUID appointment=uuid(e.payload(),"appointmentId");
      if(appointment!=null){recovery.ensureAppointmentEntity(appointment);return;}
      String serviceDate=text(e.payload(),"serviceDate"); if(serviceDate!=null)recovery.ensureFull("appointments",LocalDate.parse(serviceDate));
    } else if("wash-administration".equals(e.producer())) recovery.ensureFull("wash-administration",null);
    else if("authorization".equals(e.producer())) recovery.ensureFull("authorization",null);
  }
  private static String text(JsonNode p,String f){JsonNode v=p==null?null:p.get(f);return v==null||v.isNull()?null:v.asText();}
  private static UUID uuid(JsonNode p,String f){String v=text(p,f);return v==null||v.isBlank()?null:UUID.fromString(v);}
}
