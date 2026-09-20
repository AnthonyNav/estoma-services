package com.estoma.platformbff.infrastructure.persistence;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.estoma.platformbff.api.wash.OperationalResourcesController;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.util.UUID;
import java.util.Map;
import java.time.Instant;
import com.estoma.platformbff.application.projection.ProjectionEvent;
import com.estoma.platformbff.application.projection.ProjectionEventRuntime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@TestPropertySource(properties = "spring.flyway.enabled=true")
@Tag("integration")
class OperationalResourcesPostgresIT {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
      .withDatabaseName("resources_it").withUsername("bff").withPassword("bff");
  @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }
  @Autowired JdbcClient jdbc;
  @Autowired ProjectionReadinessService readiness;
  @Autowired ProjectionEventRuntime runtime;
  private OperationalResourcesController controller;
  private LocalAuthorizationService authorization;
  @BeforeEach void setup() {
    jdbc.sql("TRUNCATE wash_cabin_read_model, wash_tank_read_model, wash_operational_resource_unavailability_read_model CASCADE").update();
    jdbc.sql("UPDATE projection_status SET status = 'READY' WHERE projection_name IN ('wash-administration', 'wash-execution')").update();
    authorization = mock(LocalAuthorizationService.class);
    controller = new OperationalResourcesController(jdbc, authorization, readiness);
  }
  @Test void readsCabinsAndTanksWithIndependentAdministrativeAndOperationalStatuses() {
    UUID activeCabin = cabin("ACTIVE", "C1"), inactiveCabin = cabin("INACTIVE", "C2");
    UUID activeTank = tank("ACTIVE", "T1"), inactiveTank = tank("INACTIVE", "T2");
    UUID disabledCabin = unavailability(activeCabin, "CABIN", "DISABLED", 2);
    UUID disabledTank = unavailability(activeTank, "TANK", "DISABLED", 5);
    unavailability(activeTank, "TANK", "RESTORED", 6);
    var resources = controller.resources().resources();
    assertThat(resources).hasSize(4);
    assertThat(resources.get(0).resourceId()).isEqualTo(activeCabin);
    assertThat(resources.get(0).administrativeStatus()).isEqualTo("ACTIVE");
    assertThat(resources.get(0).unavailabilities()).containsExactly(new OperationalResourcesController.Unavailability(disabledCabin, "Mantenimiento", 2));
    assertThat(resources.get(1).resourceId()).isEqualTo(inactiveCabin);
    assertThat(resources.get(1).administrativeStatus()).isEqualTo("INACTIVE");
    assertThat(resources.get(1).unavailabilities()).isEmpty();
    assertThat(resources.get(2).resourceId()).isEqualTo(activeTank);
    assertThat(resources.get(2).unavailabilities()).containsExactly(new OperationalResourcesController.Unavailability(disabledTank, "Mantenimiento", 5));
    assertThat(resources.get(3).resourceId()).isEqualTo(inactiveTank);
    assertThat(resources.get(3).administrativeStatus()).isEqualTo("INACTIVE");
    assertThat(resources.get(3).unavailabilities()).isEmpty();
    verify(authorization).requireWashSupervisor();
  }
  @Test void canonicalOwnerResourceEventsPopulateInventoryAndRemainIdempotent() {
    UUID cabinId = UUID.randomUUID(), tankId = UUID.randomUUID();
    Instant now = Instant.now();
    var cabin = new ProjectionEvent(UUID.randomUUID(), "CabinRegistered", 1, "wash-administration",
        now, UUID.randomUUID(), null, cabinId, 1,
        Map.of("cabinId", cabinId.toString(), "code", "C1", "name", "Cabina", "cabinType", "NORMAL",
            "status", "ACTIVE", "version", 1, "occurredAt", now.toString()));
    var tank = new ProjectionEvent(UUID.randomUUID(), "TankRegistered", 1, "wash-administration",
        now, UUID.randomUUID(), null, tankId, 1,
        Map.of("tankId", tankId.toString(), "cabinId", cabinId.toString(), "code", "T1", "name", "Tina",
            "configuredCapacity", 2, "status", "ACTIVE", "version", 1, "occurredAt", now.toString()));
    assertThat(runtime.dispatch(cabin)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(tank)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(runtime.dispatch(cabin)).isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
    assertThat(controller.resources().resources()).extracting(OperationalResourcesController.Resource::resourceId)
        .containsExactly(cabinId, tankId);
    for (String kind : new String[] {"Cabin", "Tank"}) {
      UUID id = kind.equals("Cabin") ? cabinId : tankId;
      String idField = kind.equals("Cabin") ? "cabinId" : "tankId";
      int version = 1;
      for (String change : new String[] {"Updated", "Deactivated", "Activated", "Retired"}) {
        var payload = new java.util.HashMap<String, Object>();
        payload.put(idField, id.toString());
        payload.put("version", ++version);
        payload.put("occurredAt", now.toString());
        payload.put("status", change.equals("Retired") ? "RETIRED" : change.equals("Deactivated") ? "INACTIVE" : "ACTIVE");
        if (change.equals("Updated")) {
          payload.put("name", kind + " updated");
          if (kind.equals("Cabin")) payload.put("cabinType", "NORMAL");
          else { payload.put("cabinId", cabinId.toString()); payload.put("configuredCapacity", 3); }
        }
        assertThat(runtime.dispatch(new ProjectionEvent(UUID.randomUUID(), kind + change, 1,
            "wash-administration", now, UUID.randomUUID(), null, id, version, payload)))
            .isEqualTo(ProjectionEventRuntime.DispatchOutcome.APPLIED);
      }
    }
    assertThat(controller.resources().resources()).extracting(OperationalResourcesController.Resource::administrativeStatus)
        .containsExactly("RETIRED", "RETIRED");
    assertThat(jdbc.sql("SELECT configured_capacity FROM wash_tank_read_model WHERE tank_id = :id")
        .param("id", tankId).query(Integer.class).single()).isEqualTo(3);
  }
  @Test void realProjectionReadinessFailsClosed() {
    jdbc.sql("UPDATE projection_status SET status = 'REBUILDING' WHERE projection_name = 'wash-execution'").update();
    assertThatThrownBy(controller::resources).isInstanceOf(ProjectionUnavailableException.class);
  }
  @Test void failsClosedForIncompleteProjectedResourceAndInvalidRestoreVersion() {
    UUID cabin = cabin("ACTIVE", "C1");
    unavailability(cabin, "CABIN", "DISABLED", 0);
    assertThatThrownBy(controller::resources).isInstanceOf(ProjectionUnavailableException.class);
    jdbc.sql("DELETE FROM wash_operational_resource_unavailability_read_model").update();
    jdbc.sql("UPDATE wash_cabin_read_model SET name = NULL").update();
    assertThatThrownBy(controller::resources).isInstanceOf(ProjectionUnavailableException.class);
  }
  @Test void rejectsOversizedSnapshotInsteadOfReturningPartialResources() {
    jdbc.sql("""
        INSERT INTO wash_cabin_read_model(cabin_id, code, name, status, source_version, updated_at)
        SELECT gen_random_uuid(), 'C' || n, 'Cabina', 'ACTIVE', 1, now() FROM generate_series(1, 10001) n
        """).update();
    assertThatThrownBy(controller::resources).isInstanceOf(ProjectionUnavailableException.class)
        .hasMessageContaining("bounded read limit");
  }
  private UUID cabin(String status, String code) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO wash_cabin_read_model(cabin_id, code, name, status, source_version, updated_at) VALUES (?, ?, 'Cabina', ?, 1, now())")
        .param(id).param(code).param(status).update(); return id;
  }
  private UUID tank(String status, String code) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO wash_tank_read_model(tank_id, code, name, status, source_version, updated_at) VALUES (?, ?, 'Tina', ?, 1, now())")
        .param(id).param(code).param(status).update(); return id;
  }
  private UUID unavailability(UUID resource, String type, String status, int version) {
    UUID id = UUID.randomUUID();
    jdbc.sql("INSERT INTO wash_operational_resource_unavailability_read_model VALUES (?, ?, ?, ?, 'Mantenimiento', ?, now())")
        .param(id).param(type).param(resource).param(status).param(version).update(); return id;
  }
}
