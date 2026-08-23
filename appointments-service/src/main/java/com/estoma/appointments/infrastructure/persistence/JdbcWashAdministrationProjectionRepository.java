package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.WashAdministrationProjectionRepository;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWashAdministrationProjectionRepository implements WashAdministrationProjectionRepository {
  private static final Set<String> TABLES = Set.of("SERVICE_CALENDAR", "SERVICE_SCHEDULE", "ADMINISTRATIVE_CLOSURE", "CABIN", "TANK", "RESOURCE_PRIORITY", "RESOURCE_UNAVAILABILITY", "CLEANING_PERIOD");
  private final JdbcClient jdbc;
  public JdbcWashAdministrationProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public void upsert(AdministrationProjection p) {
    String table = switch (p.projectionType()) {
      case "SERVICE_CALENDAR" -> "service_calendar_projection";
      case "SERVICE_SCHEDULE" -> "service_schedule_projection";
      case "ADMINISTRATIVE_CLOSURE" -> "administrative_closure_projection";
      case "CABIN" -> "cabin_projection";
      case "TANK" -> "tank_projection";
      case "RESOURCE_PRIORITY" -> "resource_priority_projection";
      case "RESOURCE_UNAVAILABILITY" -> "resource_unavailability_projection";
      case "CLEANING_PERIOD" -> "cleaning_period_projection";
      default -> throw new IllegalArgumentException("unknown administration projection: " + p.projectionType());
    };
    if (p.projectionType().equals("TANK")) {
      jdbc.sql("INSERT INTO tank_projection (source_id,configured_capacity,source_version,payload,updated_at) VALUES (:source,0,:version,CAST(:payload AS jsonb),:updated) ON CONFLICT (source_id) DO UPDATE SET source_version=EXCLUDED.source_version,payload=EXCLUDED.payload,updated_at=EXCLUDED.updated_at WHERE tank_projection.source_version < EXCLUDED.source_version")
          .param("source",p.sourceId()).param("version",p.sourceVersion()).param("payload",p.payloadJson()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
    } else if (p.projectionType().equals("CLEANING_PERIOD")) {
      jdbc.sql("INSERT INTO cleaning_period_projection (source_id,source_version,starts_at,expected_ends_at,payload,updated_at) VALUES (:source,:version,:updated,:updated + interval '3 hours',CAST(:payload AS jsonb),:updated) ON CONFLICT (source_id) DO UPDATE SET source_version=EXCLUDED.source_version,payload=EXCLUDED.payload,updated_at=EXCLUDED.updated_at WHERE cleaning_period_projection.source_version < EXCLUDED.source_version")
          .param("source",p.sourceId()).param("version",p.sourceVersion()).param("payload",p.payloadJson()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
    } else {
      jdbc.sql("INSERT INTO " + table + " (source_id,source_version,payload,updated_at) VALUES (:source,:version,CAST(:payload AS jsonb),:updated) ON CONFLICT (source_id) DO UPDATE SET source_version=EXCLUDED.source_version,payload=EXCLUDED.payload,updated_at=EXCLUDED.updated_at WHERE " + table + ".source_version < EXCLUDED.source_version")
          .param("source",p.sourceId()).param("version",p.sourceVersion()).param("payload",p.payloadJson()).param("updated",Timestamps.toTimestamp(p.updatedAt())).update();
    }
  }
  @Override public boolean reliable() {
    return jdbc.sql("SELECT COALESCE(bool_and(reliable),true) FROM (SELECT reliable FROM service_calendar_projection UNION ALL SELECT reliable FROM service_schedule_projection UNION ALL SELECT reliable FROM administrative_closure_projection UNION ALL SELECT reliable FROM cabin_projection UNION ALL SELECT reliable FROM tank_projection UNION ALL SELECT reliable FROM resource_priority_projection UNION ALL SELECT reliable FROM resource_unavailability_projection UNION ALL SELECT reliable FROM cleaning_period_projection) p").query(Boolean.class).single();
  }
}
