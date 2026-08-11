package com.estoma.academic.domain;

import java.util.UUID;

public record CourseSection(UUID id, UUID academicPeriodId, String nrc, String name, Status status, int version) {
  public enum Status { ACTIVE, INACTIVE }
  public CourseSection {
    if (id == null || academicPeriodId == null || blank(nrc) || blank(name) || status == null || version < 1) {
      throw new IllegalArgumentException("invalid course section");
    }
  }
  private static boolean blank(String value) { return value == null || value.isBlank(); }
}
