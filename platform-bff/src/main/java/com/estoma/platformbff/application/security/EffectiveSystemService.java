package com.estoma.platformbff.application.security;

import com.estoma.platformbff.application.port.ProjectionQueryPort;
import com.estoma.platformbff.application.projection.ProjectionReadinessService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** The sole BFF composition of owner-published system state into current effective access. */
@Service
public class EffectiveSystemService {
  public static final String PRACTICES_SYSTEM_CODE = "PRACTICAS_PROFESIONALES";

  private final ProjectionQueryPort projections;
  private final ProjectionReadinessService readiness;

  public EffectiveSystemService(ProjectionQueryPort projections, ProjectionReadinessService readiness) {
    this.projections = projections;
    this.readiness = readiness;
  }

  public List<String> availableSystemCodes(UUID accountId) {
    if (accountId == null) throw new IllegalArgumentException("accountId is required");
    readiness.requireReady("authorization", "system-catalog");
    List<String> candidates = projections.findActiveSystemCandidates(accountId);
    if (!candidates.contains(PRACTICES_SYSTEM_CODE)) return candidates;
    // Academic is required only when its closed V1 rule can change this account's availability.
    readiness.requireReady("academic");
    return candidates.stream()
        .filter(code -> !PRACTICES_SYSTEM_CODE.equals(code)
            || projections.hasEligibleAcademicSystem(accountId, code))
        .toList();
  }

  public void requireEffectiveSystem(UUID accountId, String systemCode) {
    if (systemCode == null || systemCode.isBlank() || !availableSystemCodes(accountId).contains(systemCode)) {
      throw new LocalAuthorizationService.AuthorizationDeniedException();
    }
  }
}
