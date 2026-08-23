package com.estoma.appointments.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AcademicProjectionRepository {
  void upsertAcademicPeriod(AcademicPeriodProjection projection);
  void upsertStudentProfile(StudentAcademicProfileProjection projection);
  void upsertStudentCourse(StudentCourseSectionProjection projection);

  Optional<AcademicPeriodProjection> findPeriod(UUID academicPeriodId);
  Optional<AcademicPeriodProjection> findActivePeriod();
  Optional<StudentAcademicProfileProjection> findStudentProfile(UUID studentAccountId);
  Optional<StudentCourseSectionProjection> findStudentCourse(UUID studentAccountId, UUID courseSectionId);
  boolean reliable();

  record AcademicPeriodProjection(UUID academicPeriodId, String code, String status,
      long sourceVersion, Instant updatedAt, boolean reliable) {}

  record StudentAcademicProfileProjection(UUID academicProfileId, UUID studentAccountId,
      int currentSemester, String academicStatus, long sourceVersion, Instant updatedAt,
      boolean reliable) {}

  record StudentCourseSectionProjection(UUID academicProfileId, UUID studentAccountId,
      UUID courseSectionId, UUID academicPeriodId, Instant enrolledAt, Instant removedAt,
      long sourceVersion, Instant updatedAt, boolean reliable) {
    public boolean active() { return removedAt == null; }
  }
}
