package com.estoma.platformbff.application.commands;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.platformbff.contracts.messaging.OwnerRoute.ActorMode;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {
  private final CommandRegistry registry = new CommandRegistry();

  @Test
  void declaresOwnerResultWireContractsExplicitly() {
    assertThat(registry.require("CreatePerson").resultMessageVersions())
        .containsEntry("CreatePersonSucceeded", 1)
        .containsEntry("CreatePersonRejected", 1);
    assertThat(registry.require("CreateAccount").resultMessageVersions())
        .containsExactlyEntriesOf(java.util.Map.of("CreateAccountResult", 1));
    assertThat(registry.require("AssignAccountRole").resultMessageVersions())
        .containsExactlyEntriesOf(java.util.Map.of("AssignAccountRoleResult", 1));
    assertThat(registry.require("StartStudentImport").resultMessageVersions())
        .containsExactlyEntriesOf(java.util.Map.of("StartStudentImportResult", 1));
    assertThat(registry.require("RegisterInboxVisit").resultMessageVersions())
        .containsEntry("RegisterInboxVisitSucceeded", 1)
        .containsEntry("RegisterInboxVisitRejected", 1);
    assertThat(registry.require("CreateServiceCalendar").resultMessageVersions())
        .containsExactlyEntriesOf(java.util.Map.of("CreateServiceCalendarResult", 1));
    assertThat(registry.require("ScheduleAppointment").resultMessageVersions())
        .containsEntry("ScheduleAppointmentSucceeded", 1)
        .containsEntry("ScheduleAppointmentRejected", 1);
    assertThat(registry.require("CreateAppointmentTimeSlot").resultMessageVersions())
        .containsEntry("CreateAppointmentTimeSlotSucceeded", 1)
        .containsEntry("CreateAppointmentTimeSlotRejected", 1);
    assertThat(registry.require("RegisterWashArrival").resultMessageVersions())
        .containsEntry("RegisterWashArrivalSucceeded", 1)
        .containsEntry("RegisterWashArrivalRejected", 1);
  }

  @Test
  void onlyPasswordRecoveryUsesAnonymousDurableActor() {
    assertThat(registry.require("RequestPasswordRecovery").actorMode()).isEqualTo(ActorMode.ANONYMOUS);
    var anonymousCommands = registry.messageTypes().stream()
        .filter(type -> registry.require(type).actorMode() == ActorMode.ANONYMOUS)
        .sorted()
        .toList();
    assertThat(anonymousCommands).containsExactly("RequestPasswordRecovery");
  }

  @Test
  void appointmentTimeSlotCancellationRemainsContractGated() {
    assertThatThrownBy(() -> registry.require("CancelAppointmentTimeSlot"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
