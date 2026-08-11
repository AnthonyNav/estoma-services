package com.estoma.academic.domain;

import java.util.Locale;
import java.util.UUID;

/** Academic facts owned by this service. */
public record StudentAcademicProfile(
    UUID id, UUID personId, UUID accountId, String studentNumber, int currentSemester, Status status, int version) {
  public enum Status { ACTIVE, INACTIVE }

  public StudentAcademicProfile {
    if (id == null || personId == null || accountId == null || studentNumber == null || studentNumber.isBlank()) {
      throw new IllegalArgumentException("profile identifiers and student number are required");
    }
    validateSemester(currentSemester);
    if (status == null || version < 1) throw new IllegalArgumentException("invalid academic profile state");
  }

  public static void validateSemester(int semester) {
    if (semester < 1 || semester > 14) throw new IllegalArgumentException("invalid semester");
  }

  public static String normalizedStudentNumber(String value) {
    if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException("student number is required");
    return value.trim().toUpperCase(Locale.ROOT);
  }
}
