package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.application.commands.CommandRegistry;
import com.estoma.platformbff.application.projection.AuthorizationRecoveryService;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.util.Set;
import org.springframework.stereotype.Service;

@Service
public class ResultDispatcher {
  private static final Set<String> RECOVERY_RESULT_TYPES = Set.of(
      "GetAccountAuthorizationSnapshotResult",
      "ListAccountAuthorizationSnapshotsPageResult");

  public enum DispatchOutcome {
    APPLIED,
    RECOVERY_NOT_ENABLED,
    UNTRUSTED
  }

  private final CommandRegistry registry;
  private final ResultApplicationService commandResults;
  private final AuthorizationRecoveryService recovery;

  public ResultDispatcher(CommandRegistry registry, ResultApplicationService commandResults,
      AuthorizationRecoveryService recovery) {
    this.registry = registry;
    this.commandResults = commandResults;
    this.recovery = recovery;
  }

  public DispatchOutcome dispatch(IncomingResult result) {
    if (result == null || result.messageKind() != MessageKind.RESULT) {
      return DispatchOutcome.UNTRUSTED;
    }
    if (RECOVERY_RESULT_TYPES.contains(result.messageType())) {
      try {
        return recovery.applyResult(result) ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
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
