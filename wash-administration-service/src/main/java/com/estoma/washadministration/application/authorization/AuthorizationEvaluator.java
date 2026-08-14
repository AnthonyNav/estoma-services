package com.estoma.washadministration.application.authorization;

import com.estoma.washadministration.application.command.ActorContext;
import com.estoma.washadministration.application.command.CommandRejectedException;
import com.estoma.washadministration.application.port.AuthorizationAccessProjectionRepository;
import com.estoma.washadministration.application.port.AuthorizationRoleProjectionRepository;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Evaluates V1 authorization from local projections only. */
@Component
public final class AuthorizationEvaluator {

  private static final String SYSTEM = "LAVADO_ULTRASONICO";
  private static final String ROLE = "ADMINISTRADOR_LAVADO";
  private static final Set<String> ADMIN_PERMISSIONS = Set.of(
      "wash.admin.calendar.create",
      "wash.admin.calendar.update",
      "wash.admin.calendar.activate",
      "wash.admin.calendar.deactivate",
      "wash.admin.schedule.configure",
      "wash.admin.schedule.update",
      "wash.admin.schedule.deactivate",
      "wash.admin.closure.schedule",
      "wash.admin.closure.cancel",
      "wash.admin.cabin.register",
      "wash.admin.cabin.update",
      "wash.admin.cabin.activate",
      "wash.admin.cabin.deactivate",
      "wash.admin.cabin.retire",
      "wash.admin.tank.register",
      "wash.admin.tank.update",
      "wash.admin.tank.activate",
      "wash.admin.tank.deactivate",
      "wash.admin.tank.retire");

  private final AuthorizationRoleProjectionRepository roles;
  private final AuthorizationAccessProjectionRepository accesses;

  public AuthorizationEvaluator(
      AuthorizationRoleProjectionRepository roles,
      AuthorizationAccessProjectionRepository accesses) {
    this.roles = roles;
    this.accesses = accesses;
  }

  public boolean isAuthorized(ActorContext actor, String operationType) {
    if (actor == null || !"ACCOUNT".equals(actor.type()) || actor.accountId() == null) {
      return false;
    }
    String permission = permissionFor(operationType);
    if (permission == null) {
      return false;
    }
    UUID accountId = actor.accountId();
    return roles.findByAccountId(accountId)
            .map(AuthorizationRoleProjectionRepository.RoleProjection::roleCode)
            .filter(ROLE::equals)
            .isPresent()
        && accesses.findByAccountId(accountId, SYSTEM)
            .map(AuthorizationAccessProjectionRepository.AccessProjection::accessStatus)
            .filter("ACTIVE"::equals)
            .isPresent()
        && ADMIN_PERMISSIONS.contains(permission);
  }

  public void require(ActorContext actor, String operationType) {
    if (!isAuthorized(actor, operationType)) {
      throw new CommandRejectedException("ACTOR_NOT_AUTHORIZED");
    }
  }

  public static String permissionFor(String operationType) {
    return switch (operationType) {
      case "CreateServiceCalendar" -> "wash.admin.calendar.create";
      case "UpdateServiceCalendar" -> "wash.admin.calendar.update";
      case "ActivateServiceCalendar" -> "wash.admin.calendar.activate";
      case "DeactivateServiceCalendar" -> "wash.admin.calendar.deactivate";
      case "ConfigureServiceSchedule" -> "wash.admin.schedule.configure";
      case "UpdateServiceSchedule" -> "wash.admin.schedule.update";
      case "DeactivateServiceSchedule" -> "wash.admin.schedule.deactivate";
      case "ScheduleAdministrativeClosure" -> "wash.admin.closure.schedule";
      case "CancelAdministrativeClosure" -> "wash.admin.closure.cancel";
      case "RegisterCabin" -> "wash.admin.cabin.register";
      case "UpdateCabin" -> "wash.admin.cabin.update";
      case "ActivateCabin" -> "wash.admin.cabin.activate";
      case "DeactivateCabin" -> "wash.admin.cabin.deactivate";
      case "RetireCabin" -> "wash.admin.cabin.retire";
      case "RegisterTank" -> "wash.admin.tank.register";
      case "UpdateTank" -> "wash.admin.tank.update";
      case "ActivateTank" -> "wash.admin.tank.activate";
      case "DeactivateTank" -> "wash.admin.tank.deactivate";
      case "RetireTank" -> "wash.admin.tank.retire";
      default -> null;
    };
  }
}
