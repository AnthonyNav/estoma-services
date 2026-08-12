package com.estoma.academic.domain;

import java.util.UUID;

public record StudentCourseSection(UUID enrollmentId, UUID studentAcademicProfileId, UUID courseSectionId, Status status, int version) {
  public enum Status { ACTIVE, REMOVED }
  public StudentCourseSection {
    if (enrollmentId == null || studentAcademicProfileId == null || courseSectionId == null || status == null || version < 1) {
      throw new IllegalArgumentException("invalid enrollment");
    }
  }
}
