package com.estoma.platformbff.api.wash;

import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Operational status is independent of administrative activation and current occupancy. */
@RestController
public class OperationalResourcesController {
  private final JdbcClient jdbc;
  private final LocalAuthorizationService authorization;
  private final ProjectionReadinessService readiness;

  public OperationalResourcesController(JdbcClient jdbc, LocalAuthorizationService authorization,
      ProjectionReadinessService readiness) {
    this.jdbc = jdbc;
    this.authorization = authorization;
    this.readiness = readiness;
  }

  public record Unavailability(UUID resourceUnavailabilityId, String reason, long expectedVersion) {}
  public record Resource(UUID resourceId, String resourceType, String code, String name,
      String administrativeStatus, List<Unavailability> unavailabilities) {}
  public record Response(List<Resource> resources) {}

  @GetMapping("/api/v1/wash/operational-resources")
  public Response resources() {
    authorization.requireWashSupervisor();
    readiness.requireReady("wash-administration", "wash-execution");
    // One bounded SQL statement gives a consistent PostgreSQL MVCC snapshot without N+1 reads.
    return jdbc.sql("""
        WITH resources AS (
          SELECT cabin_id AS resource_id, 'CABIN' AS resource_type, code, name, status, source_version
          FROM wash_cabin_read_model
          UNION ALL
          SELECT tank_id, 'TANK', code, name, status, source_version FROM wash_tank_read_model
        )
        SELECT r.*, u.resource_unavailability_id, u.reason, u.source_version AS unavailability_version
        FROM resources r
        LEFT JOIN wash_operational_resource_unavailability_read_model u
          ON u.resource_id = r.resource_id AND u.resource_type = r.resource_type AND u.status = 'DISABLED'
        ORDER BY r.resource_type, r.code, r.resource_id, u.resource_unavailability_id
        LIMIT 10001
        """).query(rs -> {
        var resources = new LinkedHashMap<String, Resource>();
        int rows = 0;
        while (rs.next()) {
          if (++rows > 10000) {
            throw new ProjectionUnavailableException("operational resource projection exceeds bounded read limit");
          }
          UUID id = rs.getObject("resource_id", UUID.class);
          String type = rs.getString("resource_type");
          String code = rs.getString("code"), name = rs.getString("name"), status = rs.getString("status");
          if (code == null || code.isBlank() || name == null || name.isBlank()
              || status == null || rs.getLong("source_version") <= 0) {
            throw new ProjectionUnavailableException("operational resource projection is incomplete");
          }
          Resource resource = resources.computeIfAbsent(type + ":" + id,
              key -> new Resource(id, type, code, name, status, new ArrayList<>()));
          UUID unavailableId = rs.getObject("resource_unavailability_id", UUID.class);
          if (unavailableId != null) {
            long version = rs.getLong("unavailability_version");
            if (version <= 0 || version > Integer.MAX_VALUE || rs.getString("reason") == null) {
              throw new ProjectionUnavailableException("operational unavailability projection is incomplete");
            }
            resource.unavailabilities().add(new Unavailability(unavailableId, rs.getString("reason"), version));
          }
        }
        return new Response(List.copyOf(resources.values()));
        });
  }
}
