package com.estoma.washexecution.application.port;

import com.estoma.washexecution.domain.execution.AppointmentType;
import com.estoma.washexecution.domain.execution.CabinType;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public final class ProjectionRepositories {
  private ProjectionRepositories() {}

  public interface AppointmentRepository {
    Optional<AppointmentContext> findById(UUID appointmentId);
    void upsert(AppointmentContext context);
  }

  public interface TimeSlotRepository {
    Optional<TimeSlotContext> findById(UUID slotId);
    void upsert(TimeSlotContext context);
  }

  public interface ResourceProjectionRepository {
    List<ResourceCandidate> findCandidates(AppointmentType type, UUID slotId);
    Optional<ResourceCandidate> findCandidate(UUID cabinId, UUID tankId, UUID slotId);
    Optional<CabinProjection> findCabin(UUID cabinId);
    Optional<TankProjection> findTank(UUID tankId);
    void upsertCabin(CabinProjection cabin);
    void upsertTank(TankProjection tank);
    void upsertPriority(ResourcePriorityProjection priority);
  }

  public interface AdministrativeClosureRepository {
    Optional<AdministrativeClosureProjection> findById(UUID closureId);
    List<AdministrativeClosureProjection> findScheduledOverlapping(Instant startsAt, Instant endsAt);
    void upsert(AdministrativeClosureProjection closure);
  }

  public interface AuthorizationProjectionRepository {
    Optional<RoleProjection> role(UUID accountId);
    Optional<AccessProjection> access(UUID accountId, String systemCode);
    void upsertRole(RoleProjection role);
    void upsertAccess(AccessProjection access);
  }

  public record AppointmentContext(
      UUID appointmentId,
      UUID studentAccountId,
      UUID appointmentTimeSlotId,
      UUID courseSectionId,
      AppointmentType appointmentType,
      int instrumentCount,
      String pieceType,
      String status,
      Instant entryAuthorizedAt,
      long sourceVersion) {}

  public record TimeSlotContext(
      UUID appointmentTimeSlotId,
      UUID calendarId,
      LocalDate serviceDate,
      Instant startsAt,
      Instant endsAt,
      String timezone,
      Instant arrivalToleranceUntil,
      String status,
      long sourceVersion) {}

  public record CabinProjection(
      UUID cabinId, String code, String name, CabinType type, String status, long sourceVersion) {}

  public record TankProjection(
      UUID tankId, UUID cabinId, String code, String name, int configuredCapacity,
      String status, long sourceVersion) {}

  public record ResourcePriorityProjection(
      UUID resourcePriorityId, AppointmentType appointmentType, CabinType cabinType,
      String usageMode, int priorityOrder, String status, long sourceVersion) {}

  public record AdministrativeClosureProjection(
      UUID administrativeClosureId, UUID calendarId, String scope, UUID cabinId, UUID tankId,
      Instant startsAt, Instant endsAt, String status, long sourceVersion) {}

  public record RoleProjection(UUID accountId, String roleCode, long roleSourceVersion) {}

  public record AccessProjection(
      UUID accountId, UUID systemId, String systemCode, String accessStatus, long accessSourceVersion) {}

  public record ResourceCandidate(
      UUID cabinId,
      UUID tankId,
      String cabinCode,
      String tankCode,
      CabinType cabinType,
      String cabinStatus,
      String tankStatus,
      int configuredCapacity,
      int activeAssignmentCount,
      int priorityOrder) {
    public int remainingCapacity() { return configuredCapacity - activeAssignmentCount; }
  }
}
