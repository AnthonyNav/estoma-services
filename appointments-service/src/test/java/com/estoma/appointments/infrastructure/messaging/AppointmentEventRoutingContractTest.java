package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.appointments.contracts.MessageKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AppointmentEventRoutingContractTest {
  @Test
  void executionRelevantEventsUseCanonicalSubjectEventRouting() {
    Map<String, String> expected = Map.of(
        "AppointmentTimeSlotCreated", "appointments.appointment-time-slot.created",
        "AppointmentTimeSlotCancelled", "appointments.appointment-time-slot.cancelled",
        "AppointmentScheduled", "appointments.appointment.scheduled",
        "AppointmentCancelled", "appointments.appointment.cancelled",
        "AppointmentMissed", "appointments.appointment.missed");

    expected.forEach((messageType, routingKey) ->
        assertThat(OutboxRelay.routingKey(MessageKind.EVENT, messageType)).isEqualTo(routingKey));
  }

  @Test
  void allOwnedEventTypesHaveAnExplicitRoutingKey() {
    assertThat(OutboxRelay.eventRoutingKey("AppointmentTimeSlotCapacityRecalculated"))
        .isEqualTo("appointments.appointment-time-slot.capacity-recalculated");
    assertThat(OutboxRelay.eventRoutingKey("AppointmentTimeSlotCapacityReduced"))
        .isEqualTo("appointments.appointment-time-slot.capacity-reduced");
    assertThat(OutboxRelay.eventRoutingKey("AppointmentEntryRejected"))
        .isEqualTo("appointments.appointment.entry-rejected");
    assertThat(OutboxRelay.eventRoutingKey("AppointmentStarted"))
        .isEqualTo("appointments.appointment.started");
    assertThat(OutboxRelay.eventRoutingKey("AppointmentCompleted"))
        .isEqualTo("appointments.appointment.completed");
    assertThat(OutboxRelay.eventRoutingKey("ExceptionalAppointmentAuthorizationGranted"))
        .isEqualTo("appointments.exceptional-appointment-authorization.granted");
    assertThat(OutboxRelay.eventRoutingKey("ExceptionalAppointmentAuthorizationCancelled"))
        .isEqualTo("appointments.exceptional-appointment-authorization.cancelled");
  }
}
