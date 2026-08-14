package com.estoma.washadministration.application.priority;

import com.estoma.washadministration.application.command.ActorContext;
import com.estoma.washadministration.application.port.JsonCodec;
import com.estoma.washadministration.application.port.OutboxRepository;
import com.estoma.washadministration.application.port.OutboxRepository.OutboxMessage;
import com.estoma.washadministration.application.port.ResourcePriorityRepository;
import com.estoma.washadministration.domain.priority.AppointmentType;
import com.estoma.washadministration.domain.priority.ResourcePriority;
import com.estoma.washadministration.domain.priority.UsageMode;
import com.estoma.washadministration.domain.resource.CabinType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Idempotent internal provisioning of the fixed V1 priority matrix. */
@Component
public class ResourcePriorityConfigurator {

  private static final String PRODUCER = "wash-administration";

  private final ResourcePriorityRepository priorities;
  private final OutboxRepository outbox;
  private final JsonCodec json;

  public ResourcePriorityConfigurator(
      ResourcePriorityRepository priorities,
      OutboxRepository outbox,
      JsonCodec json) {
    this.priorities = priorities;
    this.outbox = outbox;
    this.json = json;
  }

  @Transactional
  public int configureAll(Instant now) {
    int created = 0;
    for (MatrixRow row : MatrixRow.values()) {
      ResourcePriority priority =
          ResourcePriority.createNew(
              UUID.randomUUID(),
              row.appointmentType,
              row.cabinType,
              row.usageMode,
              row.order,
              now);
      if (!priorities.insertIfAbsent(priority)) {
        continue;
      }
      created++;
      outbox.append(
          new OutboxMessage(
              UUID.randomUUID(),
              "ResourcePriorityConfigured",
              1,
              "EVENT",
              PRODUCER,
              "ResourcePriorityConfigured",
              now,
              null,
              null,
              priority.id(),
              priority.version(),
              UUID.randomUUID(),
              null,
              json.write(ActorContext.system(PRODUCER)),
              json.write(payload(priority, now)),
              null));
    }
    return created;
  }

  public List<ResourcePriority> findAll() {
    return priorities.findAll();
  }

  private static Map<String, Object> payload(ResourcePriority priority, Instant now) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("resourcePriorityId", priority.id());
    payload.put("appointmentType", priority.appointmentType());
    payload.put("cabinType", priority.cabinType());
    payload.put("usageMode", priority.usageMode());
    payload.put("priorityOrder", priority.priorityOrder());
    payload.put("status", priority.status());
    payload.put("version", priority.version());
    payload.put("occurredAt", now);
    return payload;
  }

  private enum MatrixRow {
    NORMAL_NORMAL(AppointmentType.NORMAL, CabinType.NORMAL, UsageMode.PREFERRED, 1),
    NORMAL_JOURNEY(AppointmentType.NORMAL, CabinType.JOURNEY, UsageMode.ALTERNATIVE, 2),
    JOURNEY_JOURNEY(AppointmentType.JOURNEY, CabinType.JOURNEY, UsageMode.PREFERRED, 1),
    JOURNEY_NORMAL(AppointmentType.JOURNEY, CabinType.NORMAL, UsageMode.ALTERNATIVE, 2),
    IMMUNOCOMPROMISED(
        AppointmentType.IMMUNOCOMPROMISED,
        CabinType.IMMUNOCOMPROMISED,
        UsageMode.EXCLUSIVE,
        1);

    private final AppointmentType appointmentType;
    private final CabinType cabinType;
    private final UsageMode usageMode;
    private final int order;

    MatrixRow(
        AppointmentType appointmentType,
        CabinType cabinType,
        UsageMode usageMode,
        int order) {
      this.appointmentType = appointmentType;
      this.cabinType = cabinType;
      this.usageMode = usageMode;
      this.order = order;
    }
  }
}
