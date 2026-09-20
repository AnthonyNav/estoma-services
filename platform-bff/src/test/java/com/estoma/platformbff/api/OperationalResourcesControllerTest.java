package com.estoma.platformbff.api;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.estoma.platformbff.api.wash.OperationalResourcesController;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class OperationalResourcesControllerTest {
  private JdbcClient jdbc;
  private LocalAuthorizationService authorization;
  private ProjectionReadinessService readiness;
  private OperationalResourcesController controller;
  private final UUID cabin = UUID.randomUUID();
  @BeforeEach void setup() {
    jdbc = JdbcClient.create(new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
    jdbc.sql("CREATE TABLE wash_cabin_read_model(cabin_id UUID,code VARCHAR,name VARCHAR,status VARCHAR,source_version BIGINT)").update();
    jdbc.sql("CREATE TABLE wash_tank_read_model(tank_id UUID,code VARCHAR,name VARCHAR,status VARCHAR,source_version BIGINT)").update();
    jdbc.sql("CREATE TABLE wash_operational_resource_unavailability_read_model(resource_unavailability_id UUID,resource_type VARCHAR,resource_id UUID,status VARCHAR,reason VARCHAR,source_version BIGINT)").update();
    authorization = mock(LocalAuthorizationService.class);
    readiness = mock(ProjectionReadinessService.class);
    controller = new OperationalResourcesController(jdbc, authorization, readiness);
  }
  @Test void returnsOnlyActiveUnavailabilitiesWithVersionFence() {
    jdbc.sql("INSERT INTO wash_cabin_read_model VALUES (?, 'C1', 'Cabina 1', 'ACTIVE', 2)").param(cabin).update();
    UUID active = UUID.randomUUID();
    jdbc.sql("INSERT INTO wash_operational_resource_unavailability_read_model VALUES (?, 'CABIN', ?, 'DISABLED', 'Fuga', 3)").param(active).param(cabin).update();
    jdbc.sql("INSERT INTO wash_operational_resource_unavailability_read_model VALUES (?, 'CABIN', ?, 'RESTORED', 'Anterior', 4)").param(UUID.randomUUID()).param(cabin).update();
    var resources = controller.resources().resources();
    assertThat(resources).hasSize(1);
    assertThat(resources.getFirst().unavailabilities()).containsExactly(new OperationalResourcesController.Unavailability(active, "Fuga", 3));
    verify(authorization).requireWashSupervisor();
    verify(readiness).requireReady("wash-administration", "wash-execution");
  }
  @Test void incompleteResourceFailsClosed() {
    jdbc.sql("INSERT INTO wash_cabin_read_model VALUES (?, NULL, 'Cabina', 'ACTIVE', 2)").param(cabin).update();
    assertThatThrownBy(controller::resources).isInstanceOf(ProjectionUnavailableException.class);
  }
  @Test void refusesReadsBeforeReadiness() {
    doThrow(new ProjectionUnavailableException("not ready")).when(readiness).requireReady("wash-administration", "wash-execution");
    assertThatThrownBy(controller::resources).isInstanceOf(ProjectionUnavailableException.class);
  }
  @Test void refusesNonSupervisorsBeforeProjectionRead() {
    doThrow(new SecurityException()).when(authorization).requireWashSupervisor();
    assertThatThrownBy(controller::resources).isInstanceOf(SecurityException.class);
    verifyNoInteractions(readiness);
  }
}
