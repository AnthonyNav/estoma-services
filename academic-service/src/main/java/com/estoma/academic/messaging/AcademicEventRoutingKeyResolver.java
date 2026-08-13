package com.estoma.academic.messaging;

import java.util.Map;

/** Resolves the closed V1 routing-key contract for every event Academic publishes. */
public final class AcademicEventRoutingKeyResolver {
  private static final Map<String, String> ROUTING_KEYS = Map.ofEntries(
      Map.entry("StudentAcademicProfileCreated", "academic.student.profile-created"),
      Map.entry("StudentAcademicProfileUpdated", "academic.student.profile-updated"),
      Map.entry("StudentAcademicProfileActivated", "academic.student.profile-activated"),
      Map.entry("StudentAcademicProfileDeactivated", "academic.student.profile-deactivated"),
      Map.entry("StudentAcademicEligibilityChanged", "academic.student-eligibility.changed"),
      Map.entry("StudentNumberCorrected", "academic.student.number-corrected"),
      Map.entry("StudentSemesterChanged", "academic.student.semester-changed"),
      Map.entry("AcademicPeriodActivated", "academic.period.activated"),
      Map.entry("AcademicPeriodClosed", "academic.period.closed"),
      Map.entry("AcademicPeriodDataStatusChanged", "academic.period.data-status-changed"),
      Map.entry("CourseSectionRegistered", "academic.course-section.registered"),
      Map.entry("CourseSectionUpdated", "academic.course-section.updated"),
      Map.entry("CourseSectionStatusChanged", "academic.course-section.status-changed"),
      Map.entry("StudentEnrolledInCourseSection", "academic.enrollment.registered"),
      Map.entry("StudentEnrollmentReactivated", "academic.enrollment.reactivated"),
      Map.entry("StudentRemovedFromCourseSection", "academic.enrollment.removed"),
      Map.entry("StudentImportStarted", "academic.student-import.started"));

  private AcademicEventRoutingKeyResolver() {}

  public static String resolve(String messageType) {
    String routingKey = ROUTING_KEYS.get(messageType);
    if (routingKey == null) {
      throw new IllegalArgumentException("no Academic routing key for " + messageType);
    }
    return routingKey;
  }

  public static Map<String, String> routingKeys() {
    return ROUTING_KEYS;
  }
}
