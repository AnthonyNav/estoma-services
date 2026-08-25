package com.estoma.platformbff.application.security;

import com.estoma.platformbff.application.port.CurrentActor;
import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * BFF-side authorization is intentionally limited to facts it projects locally. Owners still
 * enforce their own authorization when they consume the durable command.
 */
@Service
public class LocalAuthorizationService {
  public static final String WASH_SYSTEM_CODE = "LAVADO_ULTRASONICO";
  public static final String STUDENT_ROLE = "ALUMNO";
  public static final String WASH_SUPERVISOR_ROLE = "SUPERVISOR_LAVADO";

  private final CurrentActor currentActor;
  private final ProjectionQueryPort projections;
  private final ProjectionReadinessService readiness;

  public LocalAuthorizationService(CurrentActor currentActor, ProjectionQueryPort projections,
      ProjectionReadinessService readiness) {
    this.currentActor = currentActor;
    this.projections = projections;
    this.readiness = readiness;
  }

  public UUID requireAuthenticatedAccount() {
    return currentActor.accountId().orElseThrow(AuthenticationRequiredException::new);
  }

  public UUID requireStudent() {
    UUID accountId = requireAuthenticatedAccount();
    requireRoleAndWashAccess(accountId, STUDENT_ROLE);
    return accountId;
  }

  public UUID requireWashSupervisor() {
    UUID accountId = requireAuthenticatedAccount();
    requireRoleAndWashAccess(accountId, WASH_SUPERVISOR_ROLE);
    return accountId;
  }

  public void requireStudentOwnsAppointment(UUID appointmentId) {
    UUID accountId = requireStudent();
    readiness.requireReady("appointments");
    if (!projections.findAppointmentStudentAccountId(appointmentId).filter(accountId::equals).isPresent()) {
      throw new AuthorizationDeniedException();
    }
  }

  public void requireStudentOwnsWashExecution(UUID washExecutionId) {
    UUID accountId = requireStudent();
    readiness.requireReady("wash-execution", "appointments");
    if (!projections.findWashExecutionStudentAccountId(washExecutionId)
        .filter(accountId::equals).isPresent()) {
      throw new AuthorizationDeniedException();
    }
  }

  private void requireRoleAndWashAccess(UUID accountId, String expectedRole) {
    readiness.requireAuthorizationReady();
    boolean roleMatches = projections.findRoleCode(accountId).filter(expectedRole::equals).isPresent();
    if (!roleMatches || !projections.hasActiveSystemAccess(accountId, WASH_SYSTEM_CODE)) {
      throw new AuthorizationDeniedException();
    }
  }

  public static class AuthorizationDeniedException extends RuntimeException {
    public AuthorizationDeniedException() {
      super("The authenticated account is not authorized for this operation.");
    }
  }
}
