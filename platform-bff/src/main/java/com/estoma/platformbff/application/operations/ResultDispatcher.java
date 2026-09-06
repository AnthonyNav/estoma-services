package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.application.commands.CommandRegistry;
import com.estoma.platformbff.application.projection.AuthorizationRecoveryService;
import com.estoma.platformbff.application.projection.AccountPresentationRecoveryService;
import com.estoma.platformbff.application.projection.AppointmentQrRecoveryService;
import com.estoma.platformbff.application.projection.PersonPresentationRecoveryService;
import com.estoma.platformbff.application.projection.SystemCatalogRecoveryService;
import com.estoma.platformbff.application.wash.AppointmentAvailabilityResultService;
import com.estoma.platformbff.application.wash.ReassignmentCandidatesResultService;
import com.estoma.platformbff.contracts.messaging.MessageKind;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
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
  private static final Set<String> PERSON_PRESENTATION_RECOVERY_RESULT_TYPES = Set.of(
      "ListPersonPresentationSnapshotsPageResult");

  public enum DispatchOutcome {
    APPLIED,
    RECOVERY_NOT_ENABLED,
    UNTRUSTED
  }

  private final CommandRegistry registry;
  private final ResultApplicationService commandResults;
  private final AuthorizationRecoveryService authorizationRecovery;
  private final AccountPresentationRecoveryService accountPresentationRecovery;
  private final PersonPresentationRecoveryService personPresentationRecovery;
  private final SystemCatalogRecoveryService systemCatalogRecovery;
  private final AppointmentAvailabilityResultService appointmentAvailability;
  private final ReassignmentCandidatesResultService reassignmentCandidates;
  private final AppointmentQrRecoveryService appointmentQrRecovery;

  @Autowired
  public ResultDispatcher(CommandRegistry registry, ResultApplicationService commandResults,
      AuthorizationRecoveryService authorizationRecovery,
      AccountPresentationRecoveryService accountPresentationRecovery,
      PersonPresentationRecoveryService personPresentationRecovery,
      SystemCatalogRecoveryService systemCatalogRecovery,
      AppointmentAvailabilityResultService appointmentAvailability,
      ReassignmentCandidatesResultService reassignmentCandidates,
      AppointmentQrRecoveryService appointmentQrRecovery) {
    this.registry = registry;
    this.commandResults = commandResults;
    this.authorizationRecovery = authorizationRecovery;
    this.accountPresentationRecovery = accountPresentationRecovery;
    this.personPresentationRecovery = personPresentationRecovery;
    this.systemCatalogRecovery = systemCatalogRecovery;
    this.appointmentAvailability = appointmentAvailability;
    this.reassignmentCandidates = reassignmentCandidates;
    this.appointmentQrRecovery = appointmentQrRecovery;
  }

  /** Compatibility constructor for focused tests unrelated to Appointment QR recovery. */
  public ResultDispatcher(CommandRegistry registry, ResultApplicationService commandResults,
      AuthorizationRecoveryService authorizationRecovery,
      AccountPresentationRecoveryService accountPresentationRecovery,
      PersonPresentationRecoveryService personPresentationRecovery,
      SystemCatalogRecoveryService systemCatalogRecovery,
      AppointmentAvailabilityResultService appointmentAvailability,
      ReassignmentCandidatesResultService reassignmentCandidates) {
    this(registry, commandResults, authorizationRecovery, accountPresentationRecovery,
        personPresentationRecovery, systemCatalogRecovery, appointmentAvailability,
        reassignmentCandidates, null);
  }

  public DispatchOutcome dispatch(IncomingResult result) {
    if (result == null || result.messageKind() != MessageKind.RESULT) {
      return DispatchOutcome.UNTRUSTED;
    }
    if (AppointmentQrRecoveryService.RESULT_TYPE.equals(result.messageType())) {
      if (appointmentQrRecovery == null) return DispatchOutcome.RECOVERY_NOT_ENABLED;
      try {
        return appointmentQrRecovery.applyResult(result)
            ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
      } catch (IllegalArgumentException invalid) {
        return DispatchOutcome.UNTRUSTED;
      }
    }
    if ("GetAppointmentAvailabilityResult".equals(result.messageType())) {
      try {
        return appointmentAvailability.apply(result) ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
      } catch (IllegalArgumentException invalid) {
        return DispatchOutcome.UNTRUSTED;
      }
    }
    if ("GetReassignmentCandidatesResult".equals(result.messageType())) {
      try {
        return reassignmentCandidates.apply(result) ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
      } catch (IllegalArgumentException invalid) {
        return DispatchOutcome.UNTRUSTED;
      }
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
    if (PERSON_PRESENTATION_RECOVERY_RESULT_TYPES.contains(result.messageType())) {
      try {
        return personPresentationRecovery.applyResult(result) ? DispatchOutcome.APPLIED : DispatchOutcome.UNTRUSTED;
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
