package com.estoma.washexecution.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.washexecution.application.command.CommandRejectedException;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.application.resource.ResourceAllocator;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CabinType;
import com.estoma.washexecution.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

class ResourceAllocatorIT extends AbstractPostgresIT {
  private static final Instant START = Instant.parse("2026-08-23T10:00:00Z");

  @Autowired ResourceAllocator allocator;
  @Autowired ProjectionRepositories.AdministrativeClosureRepository closures;
  @Autowired PlatformTransactionManager transactionManager;

  @BeforeEach
  void administrationReady() {
    jdbc.update("""
        INSERT INTO projection_recovery_state(
          projection_name,source_logical_component_id,readiness_status,last_successful_rebuild_at,last_error,updated_at)
        VALUES('wash-administration','wash-administration','READY',now(),NULL,now())
        """);
  }

  @Test
  void closureOnlyBlocksMatchingCalendarAndScope() {
    UUID calendar = UUID.randomUUID();
    UUID otherCalendar = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    seedResource(cabin, tank);
    var slot = slot(slotId, calendar);

    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        UUID.randomUUID(), otherCalendar, "SERVICE", null, null,
        START.minusSeconds(60), START.plusSeconds(60 * 60L), "SCHEDULED", 1));

    var allowed = inTransaction(() -> allocator.allocateAutomatic(
        AppointmentType.NORMAL, slot, START.plusSeconds(60)));
    assertThat(allowed).isPresent().get()
        .extracting(ProjectionRepositories.ResourceCandidate::tankId).isEqualTo(tank);

    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        UUID.randomUUID(), calendar, "SERVICE", null, null,
        START.minusSeconds(60), START.plusSeconds(60 * 60L), "SCHEDULED", 1));

    var blocked = inTransaction(() -> allocator.allocateAutomatic(
        AppointmentType.NORMAL, slot, START.plusSeconds(60)));
    assertThat(blocked).isEmpty();
  }

  @Test
  void missingCalendarFailsClosedWhenARelevantClosureExists() {
    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    seedResource(cabin, tank);
    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        UUID.randomUUID(), UUID.randomUUID(), "CABIN", cabin, null,
        START.minusSeconds(60), START.plusSeconds(60 * 60L), "SCHEDULED", 1));

    var slotWithoutCalendar = slot(UUID.randomUUID(), null);
    assertThatThrownBy(() -> inTransaction(() -> allocator.allocateAutomatic(
        AppointmentType.NORMAL, slotWithoutCalendar, START.plusSeconds(60))))
        .isInstanceOf(CommandRejectedException.class)
        .hasMessage("APPOINTMENT_CONTEXT_UNAVAILABLE");
  }

  @Test
  void cancelledClosureDoesNotBlock() {
    UUID calendar = UUID.randomUUID();
    UUID cabin = UUID.randomUUID();
    UUID tank = UUID.randomUUID();
    seedResource(cabin, tank);
    UUID closureId = UUID.randomUUID();
    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        closureId, calendar, "TANK", null, tank,
        START.minusSeconds(60), START.plusSeconds(60 * 60L), "SCHEDULED", 1));
    closures.upsert(new ProjectionRepositories.AdministrativeClosureProjection(
        closureId, calendar, "TANK", null, tank,
        START.minusSeconds(60), START.plusSeconds(60 * 60L), "CANCELLED", 2));

    assertThat(inTransaction(() -> allocator.allocateAutomatic(
        AppointmentType.NORMAL, slot(UUID.randomUUID(), calendar), START.plusSeconds(60))))
        .isPresent();
  }

  @Test
  void sideEffectFreeSnapshotUsesTheSameFirstCandidateAsAutomaticAllocation() {
    UUID calendar = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    UUID normalCabin = UUID.randomUUID();
    UUID normalTank = UUID.randomUUID();
    UUID journeyCabin = UUID.randomUUID();
    UUID journeyTank = UUID.randomUUID();
    seedResource(normalCabin, normalTank, "NORMAL");
    seedResource(journeyCabin, journeyTank, "JOURNEY");
    jdbc.update("""
        INSERT INTO resource_priority_projection(
          resource_priority_id,appointment_type,cabin_type,usage_mode,priority_order,status,
          source_version,updated_at)
        VALUES
          (?,'NORMAL','NORMAL','AUTOMATIC',9,'ACTIVE',1,now()),
          (?,'NORMAL','JOURNEY','AUTOMATIC',1,'ACTIVE',1,now())
        """, UUID.randomUUID(), UUID.randomUUID());
    var slot = slot(slotId, calendar);

    var snapshot = inTransaction(() -> allocator.snapshotCandidates(
        AppointmentType.NORMAL, slot, START.plusSeconds(60)));
    var automatic = inTransaction(() -> allocator.allocateAutomatic(
        AppointmentType.NORMAL, slot, START.plusSeconds(60)));

    assertThat(snapshot).isNotEmpty();
    assertThat(automatic).isPresent();
    assertThat(snapshot.getFirst().tankId()).isEqualTo(journeyTank);
    assertThat(automatic.get().tankId()).isEqualTo(snapshot.getFirst().tankId());
    assertThat(jdbc.queryForObject(
        "SELECT count(*) FROM resource_assignments", Integer.class)).isZero();
  }

  private ProjectionRepositories.TimeSlotContext slot(UUID slotId, UUID calendarId) {
    return new ProjectionRepositories.TimeSlotContext(
        slotId,
        calendarId,
        LocalDate.of(2026, 8, 23),
        START,
        START.plusSeconds(60 * 60L),
        "UTC",
        START.plusSeconds(10 * 60L),
        "ACTIVE",
        1);
  }

  private void seedResource(UUID cabin, UUID tank) {
    seedResource(cabin, tank, "NORMAL");
  }

  private void seedResource(UUID cabin, UUID tank, String cabinType) {
    jdbc.update("""
        INSERT INTO cabin_projection(cabin_id,code,name,cabin_type,status,source_version,updated_at)
        VALUES(?,?,'Cabin',?,'ACTIVE',1,now())
        """, cabin, "C-" + cabin.toString().substring(0, 8), cabinType);
    jdbc.update("""
        INSERT INTO tank_projection(tank_id,cabin_id,code,name,configured_capacity,status,source_version,updated_at)
        VALUES(?,?,?,'Tank',2,'ACTIVE',1,now())
        """, tank, cabin, "T-" + tank.toString().substring(0, 8));
  }

  private <T> T inTransaction(java.util.concurrent.Callable<T> work) {
    TransactionTemplate tx = new TransactionTemplate(transactionManager);
    return tx.execute(status -> {
      try {
        return work.call();
      } catch (RuntimeException ex) {
        throw ex;
      } catch (Exception ex) {
        throw new IllegalStateException(ex);
      }
    });
  }
}
