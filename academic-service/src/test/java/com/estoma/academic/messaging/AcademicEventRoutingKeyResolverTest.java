package com.estoma.academic.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AcademicEventRoutingKeyResolverTest {
  @Test
  void everyProducedEventHasAnExplicitCanonicalRoute() {
    assertThat(AcademicEventRoutingKeyResolver.routingKeys()).containsEntry(
        "StudentAcademicEligibilityChanged", "academic.student-eligibility.changed");
    assertThat(AcademicEventRoutingKeyResolver.routingKeys()).containsEntry(
        "StudentNumberCorrected", "academic.student.number-corrected");
    assertThat(AcademicEventRoutingKeyResolver.routingKeys()).containsEntry(
        "AcademicPeriodActivated", "academic.period.activated");
    assertThat(AcademicEventRoutingKeyResolver.routingKeys()).containsEntry(
        "StudentEnrollmentReactivated", "academic.enrollment.reactivated");
    assertThat(AcademicEventRoutingKeyResolver.routingKeys()).containsEntry(
        "StudentImportValidated", "academic.student-import.validated");
    assertThat(AcademicEventRoutingKeyResolver.routingKeys()).containsEntry(
        "StudentImportApplicationResumed", "academic.student-import.application-resumed");
    assertThat(AcademicEventRoutingKeyResolver.routingKeys())
        .doesNotContainKey("StudentAcademicProfileUpdated")
        .doesNotContainKey("StudentImportResumed");
    assertThat(AcademicEventRoutingKeyResolver.routingKeys().values())
        .allMatch(key -> key.matches("academic\\.[a-z0-9-]+\\.[a-z0-9-]+"));
  }

  @Test
  void unknownEventCannotBePublished() {
    assertThatThrownBy(() -> AcademicEventRoutingKeyResolver.resolve("UnknownEvent"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
