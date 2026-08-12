package com.estoma.washadministration.infrastructure.persistence.jdbc;

import com.estoma.washadministration.application.port.ResourcePriorityRepository;
import com.estoma.washadministration.domain.priority.AppointmentType;
import com.estoma.washadministration.domain.priority.ResourcePriority;
import com.estoma.washadministration.domain.resource.CabinType;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcResourcePriorityRepository implements ResourcePriorityRepository {

  private static final String COLUMNS =
      "resource_priority_id, appointment_type, cabin_type, usage_mode, priority_order, status, version, created_at, updated_at";
  private final JdbcClient jdbc;

  JdbcResourcePriorityRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<ResourcePriority> findByTypePair(AppointmentType appointmentType, CabinType cabinType) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM resource_priorities WHERE appointment_type = CAST(:appointmentType AS appointment_type) "
                + "AND cabin_type = CAST(:cabinType AS cabin_type)")
        .param("appointmentType", appointmentType.name())
        .param("cabinType", cabinType.name())
        .query(PriorityRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<ResourcePriority> findAll() {
    return jdbc.sql("SELECT " + COLUMNS + " FROM resource_priorities ORDER BY appointment_type, priority_order")
        .query(PriorityRowMapper.INSTANCE)
        .list();
  }

  @Override
  public void insert(ResourcePriority priority) {
    jdbc.sql(
            """
            INSERT INTO resource_priorities (
              resource_priority_id, appointment_type, cabin_type, usage_mode, priority_order,
              status, version, created_at, updated_at
            ) VALUES (
              :id, CAST(:appointmentType AS appointment_type), CAST(:cabinType AS cabin_type),
              CAST(:usageMode AS usage_mode), :priorityOrder, CAST(:status AS resource_status),
              :version, :createdAt, :updatedAt
            )
            """)
        .param("id", priority.id())
        .param("appointmentType", priority.appointmentType().name())
        .param("cabinType", priority.cabinType().name())
        .param("usageMode", priority.usageMode().name())
        .param("priorityOrder", priority.priorityOrder())
        .param("status", priority.status().name())
        .param("version", priority.version())
        .param("createdAt", Timestamps.toTimestamp(priority.createdAt()))
        .param("updatedAt", Timestamps.toTimestamp(priority.updatedAt()))
        .update();
  }
}
