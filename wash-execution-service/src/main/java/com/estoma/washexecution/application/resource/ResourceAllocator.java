package com.estoma.washexecution.application.resource;

import com.estoma.washexecution.application.authorization.ProjectionReadinessGuard;
import com.estoma.washexecution.application.command.CommandRejectedException;
import com.estoma.washexecution.application.port.CleaningPeriodRepository;
import com.estoma.washexecution.application.port.ProjectionRepositories;
import com.estoma.washexecution.application.port.ResourceAssignmentRepository;
import com.estoma.washexecution.application.port.ResourceConcurrencyRepository;
import com.estoma.washexecution.application.port.ResourceUnavailabilityRepository;
import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CabinType;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class ResourceAllocator {
  private final ProjectionRepositories.ResourceProjectionRepository resources;
  private final ProjectionRepositories.AdministrativeClosureRepository closures;
  private final ResourceAssignmentRepository assignments;
  private final ResourceUnavailabilityRepository unavailabilities;
  private final CleaningPeriodRepository cleanings;
  private final ResourceConcurrencyRepository locks;
  private final ProjectionReadinessGuard readiness;

  public ResourceAllocator(
      ProjectionRepositories.ResourceProjectionRepository resources,
      ProjectionRepositories.AdministrativeClosureRepository closures,
      ResourceAssignmentRepository assignments,
      ResourceUnavailabilityRepository unavailabilities,
      CleaningPeriodRepository cleanings,
      ResourceConcurrencyRepository locks,
      ProjectionReadinessGuard readiness) {
    this.resources = resources;
    this.closures = closures;
    this.assignments = assignments;
    this.unavailabilities = unavailabilities;
    this.cleanings = cleanings;
    this.locks = locks;
    this.readiness = readiness;
  }

  public Optional<ProjectionRepositories.ResourceCandidate> allocateAutomatic(
      AppointmentType appointmentType,
      ProjectionRepositories.TimeSlotContext slot,
      Instant assignmentAt) {
    requireAdministrationReady();
    requireActiveSlot(slot);
    List<ProjectionRepositories.ResourceCandidate> snapshot = resources.findCandidates(
        appointmentType, slot.appointmentTimeSlotId());
    lockCandidateSet(snapshot);

    List<ProjectionRepositories.ResourceCandidate> current = snapshot.stream()
        .map(candidate -> resources.findCandidate(
            candidate.cabinId(), candidate.tankId(), slot.appointmentTimeSlotId()).orElse(null))
        .filter(Objects::nonNull)
        .sorted(businessOrder())
        .toList();
    return current.stream()
        .filter(candidate -> available(candidate, appointmentType, slot, assignmentAt))
        .findFirst();
  }

  public ProjectionRepositories.ResourceCandidate requireSelected(
      AppointmentType appointmentType,
      ProjectionRepositories.TimeSlotContext slot,
      UUID cabinId,
      UUID tankId,
      Instant assignmentAt) {
    requireAdministrationReady();
    requireActiveSlot(slot);
    if (cabinId == null || tankId == null) {
      throw new CommandRejectedException("INVALID_RESOURCE_COMBINATION");
    }
    var tank = resources.findTank(tankId)
        .orElseThrow(() -> new CommandRejectedException("INVALID_RESOURCE_COMBINATION"));
    if (!Objects.equals(tank.cabinId(), cabinId) || resources.findCabin(cabinId).isEmpty()) {
      throw new CommandRejectedException("INVALID_RESOURCE_COMBINATION");
    }
    locks.lockCabinAndTank(cabinId, tankId);
    ProjectionRepositories.ResourceCandidate candidate = resources
        .findCandidate(cabinId, tankId, slot.appointmentTimeSlotId())
        .orElseThrow(() -> new CommandRejectedException("INVALID_RESOURCE_COMBINATION"));
    if (!compatible(appointmentType, candidate.cabinType())) {
      throw new CommandRejectedException("INVALID_RESOURCE_COMBINATION");
    }
    if (!"ACTIVE".equals(candidate.cabinStatus()) || !"ACTIVE".equals(candidate.tankStatus())) {
      throw new CommandRejectedException("RESOURCE_NOT_AVAILABLE");
    }
    if (assignments.countActiveByTankAndSlot(tankId, slot.appointmentTimeSlotId())
        >= candidate.configuredCapacity()) {
      throw new CommandRejectedException("TANK_CAPACITY_EXHAUSTED");
    }
    if (!availableRestrictions(candidate, slot, assignmentAt)) {
      throw new CommandRejectedException("RESOURCE_NOT_AVAILABLE");
    }
    return candidate;
  }

  private void requireAdministrationReady() {
    readiness.requireReady(
        ProjectionReadinessGuard.WASH_ADMINISTRATION,
        "RESOURCE_CONFIGURATION_UNAVAILABLE");
  }

  private static void requireActiveSlot(ProjectionRepositories.TimeSlotContext slot) {
    if (slot == null || !"ACTIVE".equals(slot.status())) {
      throw new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE");
    }
  }

  private void lockCandidateSet(List<ProjectionRepositories.ResourceCandidate> candidates) {
    candidates.stream()
        .map(ProjectionRepositories.ResourceCandidate::cabinId)
        .distinct()
        .sorted()
        .forEach(locks::lockCabin);
    candidates.stream()
        .sorted(Comparator.comparing(ProjectionRepositories.ResourceCandidate::cabinId)
            .thenComparing(ProjectionRepositories.ResourceCandidate::tankId))
        .forEach(candidate -> locks.lockTank(candidate.cabinId(), candidate.tankId()));
  }

  private boolean available(
      ProjectionRepositories.ResourceCandidate candidate,
      AppointmentType appointmentType,
      ProjectionRepositories.TimeSlotContext slot,
      Instant assignmentAt) {
    if (!compatible(appointmentType, candidate.cabinType())) return false;
    if (!"ACTIVE".equals(candidate.cabinStatus()) || !"ACTIVE".equals(candidate.tankStatus())) return false;
    if (candidate.configuredCapacity() <= 0) return false;
    if (assignments.countActiveByTankAndSlot(candidate.tankId(), slot.appointmentTimeSlotId())
        >= candidate.configuredCapacity()) return false;
    return availableRestrictions(candidate, slot, assignmentAt);
  }

  private boolean availableRestrictions(
      ProjectionRepositories.ResourceCandidate candidate,
      ProjectionRepositories.TimeSlotContext slot,
      Instant assignmentAt) {
    if (!unavailabilities.findActiveForResource(candidate.cabinId(), candidate.tankId()).isEmpty()) {
      return false;
    }

    Instant cleaningEnd = slot.endsAt().isAfter(assignmentAt)
        ? slot.endsAt()
        : assignmentAt.plusNanos(1);
    if (!cleanings.findOverlapping(candidate.cabinId(), assignmentAt, cleaningEnd).isEmpty()) {
      return false;
    }

    List<ProjectionRepositories.AdministrativeClosureProjection> overlapping =
        closures.findScheduledOverlapping(slot.startsAt(), slot.endsAt());
    for (var closure : overlapping) {
      if (!scopeTargets(closure, candidate)) continue;
      if (slot.calendarId() == null) {
        throw new CommandRejectedException("APPOINTMENT_CONTEXT_UNAVAILABLE");
      }
      if (Objects.equals(closure.calendarId(), slot.calendarId())) {
        return false;
      }
    }
    return true;
  }

  private static boolean scopeTargets(
      ProjectionRepositories.AdministrativeClosureProjection closure,
      ProjectionRepositories.ResourceCandidate candidate) {
    return switch (closure.scope()) {
      case "SERVICE" -> true;
      case "CABIN" -> Objects.equals(closure.cabinId(), candidate.cabinId());
      case "TANK" -> Objects.equals(closure.tankId(), candidate.tankId());
      default -> false;
    };
  }

  public static boolean compatible(AppointmentType appointmentType, CabinType cabinType) {
    return switch (appointmentType) {
      case IMMUNOCOMPROMISED -> cabinType == CabinType.IMMUNOCOMPROMISED;
      case NORMAL -> cabinType == CabinType.NORMAL || cabinType == CabinType.JOURNEY;
      case JOURNEY -> cabinType == CabinType.JOURNEY || cabinType == CabinType.NORMAL;
    };
  }

  private static Comparator<ProjectionRepositories.ResourceCandidate> businessOrder() {
    return Comparator
        .comparingInt(ProjectionRepositories.ResourceCandidate::priorityOrder)
        .thenComparingInt(candidate -> candidate.activeAssignmentCount() > 0 ? 0 : 1)
        .thenComparingInt(ProjectionRepositories.ResourceCandidate::remainingCapacity)
        .thenComparing(ProjectionRepositories.ResourceCandidate::cabinCode)
        .thenComparing(ProjectionRepositories.ResourceCandidate::tankCode)
        .thenComparing(ProjectionRepositories.ResourceCandidate::cabinId)
        .thenComparing(ProjectionRepositories.ResourceCandidate::tankId);
  }
}
