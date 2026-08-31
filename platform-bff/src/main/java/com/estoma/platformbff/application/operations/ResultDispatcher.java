package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.application.commands.CommandRegistry;
import com.estoma.platformbff.application.projection.AuthorizationRecoveryService;
import com.estoma.platformbff.application.projection.AccountPresentationRecoveryService;
import com.estoma.platformbff.application.projection.SystemCatalogRecoveryService;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ResultDispatcher {
  private static final Set<String> AUTHORIZATION_RECOVERY_RESULT_TYPES = Set.of(
      "GetAccountAuthorizationSnapshotResult",
      "ListAccountAuthorizationSnapshotsPageResult");
  private static final Set<String> ACCOUNT_PRESENTATION_RECOVERY_RESULT_TYPES = Set.of(
      "GetAccountPresentationSnapshotResult",
      "ListAccountPresentationSnapshotsPageResult");
  private static final Set<String> SYSTEM_CATALOG_RECOVERY_RESULT_TYPES = Set.of(
      "GetSystemCatalogSnapshotResult",
      "ListSystemCatalogSnapshotsPageResult");

  public enum DispatchOutcome {
    APPLIED,
    RECOVERY_NOT_ENABLED,
    UNTRUSTED
  }

  private final CommandRegistry registry;
  private final ResultApplicationService commandResults;
  private final AuthorizationRecoveryService authorizationRecovery;
  private final AccountPresentationRecoveryService accountPresentationRecovery;
  private final SystemCatalogRecoveryService systemCatalogRecovery;

  public ResultDispatcher(CommandRegistry registry, ResultApplicationService commandResults,
      AuthorizationRecoveryService authorizationRecovery,
      AccountPresentationRecoveryService accountPresentationRecovery,
      SystemCatalogRecoveryService systemCatalogRecovery) {
    this.registry = registry;
    this.commandResults = commandResults;
    this.authorizationRecovery = authorizationRecovery;
    this.accountPresentationRecovery = accountPresentationRecovery;
    this.systemCatalogRecovery = systemCatalogRecovery;
  }

  public DispatchOutcome dispatch(IncomingResult result) {
    if (result == null || result.messageKind() != MessageKind.RESULT) {
      return DispatchOutcome.UNTRUSTED;
    }
    if (AUTHORIZATION_RECOVERY_RESULT_TYPES.contains(result.messageType())) {
      try {
        return authorizationRecovery.applyResult(result) ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
      } catch (IllegalArgumentException invalid) {
        return DispatchOutcome.UNTRUSTED;
      }
    }
    if (ACCOUNT_PRESENTATION_RECOVERY_RESULT_TYPES.contains(result.messageType())) {
      try {
        return accountPresentationRecovery.applyResult(result) ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
      } catch (IllegalArgumentException invalid) {
        return DispatchOutcome.UNTRUSTED;
      }
    }
    if (SYSTEM_CATALOG_RECOVERY_RESULT_TYPES.contains(result.messageType())) {
      try {
        return systemCatalogRecovery.applyResult(result) ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
      } catch (IllegalArgumentException invalid) {
        return DispatchOutcome.UNTRUSTED;
      }
    }
    if (!registry.isKnownResultType(result.messageType())) {
      return DispatchOutcome.UNTRUSTED;
    }
    try {
      return commandResults.apply(result) ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
    } catch (ResultApplicationService.ConflictingTerminalResultException conflict) {
      return DispatchOutcome.UNTRUSTED;
    }
  }
}
