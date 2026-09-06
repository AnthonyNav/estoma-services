package com.estoma.washexecution.application.resource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.washexecution.application.authorization.ProjectionReadinessGuard;
import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.ProjectionRecoveryRepository;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.ResourceConcurrencyRepository;
import com.estoma.washexecution.application.port.ResourceUnavailabilityRepository;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CabinType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ResourceAllocatorTest {
  @Test
  void automaticRereadNeverSelectsANewlyAppearingUnlockedCandidate() {
    var resources = mock(ProjectionRepositories.ResourceProjectionRepository.class);
    var closures = mock(ProjectionRepositories.AdministrativeClosureRepository.class);
    var assignments = mock(ResourceAssignmentRepository.class);
    var unavailabilities = mock(ResourceUnavailabilityRepository.class);
    var cleanings = mock(CleaningPeriodRepository.class);
    var locks = mock(ResourceConcurrencyRepository.class);
    var recovery = mock(ProjectionRecoveryRepository.class);
    var allocator = new ResourceAllocator(
        resources, closures, assignments, unavailabilities, cleanings, locks,
        new ProjectionReadinessGuard(recovery));

    UUID slotId = UUID.randomUUID();
    var initiallyVisible = candidate(9, "A");
    var newlyVisibleAndPreferred = candidate(1, "B");
    when(recovery.find(ProjectionReadinessGuard.WASH_ADMINISTRATION)).thenReturn(Optional.of(
        new ProjectionRecoveryRepository.RecoveryState(
            ProjectionReadinessGuard.WASH_ADMINISTRATION, "wash-administration", "READY", null,
            Instant.EPOCH)));
    when(resources.findCandidates(AppointmentType.NORMAL, slotId))
        .thenReturn(List.of(initiallyVisible), List.of(initiallyVisible, newlyVisibleAndPreferred));
    when(assignments.countActiveByTankAndSlot(initiallyVisible.tankId(), slotId)).thenReturn(0);
    when(unavailabilities.findActiveForResource(
        initiallyVisible.cabinId(), initiallyVisible.tankId())).thenReturn(List.of());
    when(cleanings.findOverlapping(
        initiallyVisible.cabinId(), Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)))
        .thenReturn(List.of());
    when(closures.findScheduledOverlapping(Instant.EPOCH, Instant.EPOCH.plusSeconds(3600)))
        .thenReturn(List.of());
    var slot = new ProjectionRepositories.TimeSlotContext(
        slotId, UUID.randomUUID(), LocalDate.of(2026, 9, 5), Instant.EPOCH,
        Instant.EPOCH.plusSeconds(3600), "UTC", Instant.EPOCH.plusSeconds(600), "ACTIVE", 1);

    var selected = allocator.allocateAutomatic(AppointmentType.NORMAL, slot, Instant.EPOCH);

    assertThat(selected).contains(initiallyVisible);
    verify(locks).lockCabin(initiallyVisible.cabinId());
    verify(locks).lockTank(initiallyVisible.cabinId(), initiallyVisible.tankId());
    verify(locks, never()).lockCabin(newlyVisibleAndPreferred.cabinId());
    verify(locks, never()).lockTank(
        newlyVisibleAndPreferred.cabinId(), newlyVisibleAndPreferred.tankId());
  }

  private static ProjectionRepositories.ResourceCandidate candidate(int priority, String code) {
    return new ProjectionRepositories.ResourceCandidate(
        UUID.randomUUID(), UUID.randomUUID(), code, "Cabin " + code, code, "Tank " + code,
        CabinType.NORMAL, "ACTIVE", "ACTIVE", 2, 0, priority);
  }
}
