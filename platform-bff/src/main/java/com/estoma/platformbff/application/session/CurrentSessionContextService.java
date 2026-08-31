package com.estoma.platformbff.application.session;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.security.AuthenticationRequiredException;
import com.estoma.platformbff.application.security.CurrentSecurityPrincipal;
import com.estoma.platformbff.application.security.EffectiveSystemService;
import com.estoma.platformbff.application.security.LocalAuthorizationService.AuthorizationDeniedException;
import com.estoma.platformbff.contracts.http.auth.CurrentSessionResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Assembles the authenticated client shell exclusively from the principal and local projections. */
@Service
public class CurrentSessionContextService {
  private final CurrentSecurityPrincipal principal;
  private final ProjectionReadinessService readiness;
  private final ProjectionQueryPort projections;
  private final EffectiveSystemService systems;

  public CurrentSessionContextService(CurrentSecurityPrincipal principal,
      ProjectionReadinessService readiness, ProjectionQueryPort projections, EffectiveSystemService systems) {
    this.principal = principal;
    this.readiness = readiness;
    this.projections = projections;
    this.systems = systems;
  }

  public CurrentSessionResponse current() {
    CurrentSecurityPrincipal.Principal current = principal.current()
        .orElseThrow(AuthenticationRequiredException::new);
    readiness.requireReady("person", "account-presentation", "authorization", "system-catalog");
    UUID accountId = current.accountId();
    var account = projections.findAccountPresentation(accountId)
        .orElseThrow(() -> unavailable("account presentation is incomplete"));
    if (!current.personId().equals(account.personId())) throw new AuthorizationDeniedException();
    if (!"ACTIVE".equals(account.accountStatus())) throw new AuthorizationDeniedException();
    var person = projections.findPersonPresentation(current.personId())
        .orElseThrow(() -> unavailable("person presentation is incomplete"));
    if (!"ACTIVE".equals(person.status())) throw new AuthorizationDeniedException();
    if (blank(person.institutionalEmail())) throw unavailable("institutional email is incomplete");
    String roleCode = projections.findRoleCode(accountId)
        .orElseThrow(() -> unavailable("authorization role is incomplete"));
    return new CurrentSessionResponse(accountId, current.personId(), displayName(person), account.loginType(),
        account.loginIdentifier(), person.institutionalEmail(), roleCode, systems.availableSystemCodes(accountId));
  }

  private static String displayName(ProjectionQueryPort.PersonPresentation person) {
    String value = java.util.stream.Stream.of(person.firstName(), person.paternalSurname(), person.maternalSurname())
        .filter(part -> !blank(part)).collect(java.util.stream.Collectors.joining(" "));
    if (blank(value)) throw unavailable("person display name is incomplete");
    return value;
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static ProjectionUnavailableException unavailable(String reason) {
    return new ProjectionUnavailableException(reason);
  }
}
