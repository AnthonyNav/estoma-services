package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAcademicProjectionRepository implements AcademicProjectionRepository {
  private static final String PERIOD_COLUMNS = "academic_period_id,code,status,source_version,updated_at,reliable";
  private static final String PROFILE_COLUMNS = "academic_profile_id,student_account_id,current_semester,academic_status,source_version,updated_at,reliable";
  private static final String SECTION_COLUMNS = "course_section_id,academic_period_id,status,source_version,updated_at,reliable";
  private static final String COURSE_COLUMNS = "academic_profile_id,student_account_id,course_section_id,academic_period_id,enrolled_at,removed_at,source_version,updated_at,reliable";
  private final JdbcClient jdbc;
  public JdbcAcademicProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

  @Override public void upsertAcademicPeriod(AcademicPeriodProjection p) {
    jdbc.sql("INSERT INTO academic_period_projection (academic_period_id,code,status,source_version,updated_at,reliable) VALUES (:id,:code,:status,:version,:updated,:reliable) ON CONFLICT (academic_period_id) DO UPDATE SET code=EXCLUDED.code,status=EXCLUDED.status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at,reliable=EXCLUDED.reliable WHERE academic_period_projection.source_version < EXCLUDED.source_version")
      .param("id",p.academicPeriodId()).param("code",p.code()).param("status",p.status())
      .param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).param("reliable",p.reliable()).update();
  }

  @Override public void upsertStudentProfile(StudentAcademicProfileProjection p) {
    jdbc.sql("INSERT INTO student_academic_profile_projection (academic_profile_id,student_account_id,current_semester,academic_status,source_version,updated_at,reliable) VALUES (:id,:student,:semester,:status,:version,:updated,:reliable) ON CONFLICT (academic_profile_id) DO UPDATE SET student_account_id=EXCLUDED.student_account_id,current_semester=EXCLUDED.current_semester,academic_status=EXCLUDED.academic_status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at,reliable=EXCLUDED.reliable WHERE student_academic_profile_projection.source_version < EXCLUDED.source_version")
      .param("id",p.academicProfileId()).param("student",p.studentAccountId()).param("semester",p.currentSemester())
      .param("status",p.academicStatus()).param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt()))
      .param("reliable",p.reliable()).update();
  }

  @Override public void upsertCourseSection(CourseSectionProjection p) {
    jdbc.sql("INSERT INTO course_section_projection (course_section_id,academic_period_id,status,source_version,updated_at,reliable) VALUES (:id,:period,:status,:version,:updated,:reliable) ON CONFLICT (course_section_id) DO UPDATE SET academic_period_id=EXCLUDED.academic_period_id,status=EXCLUDED.status,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at,reliable=EXCLUDED.reliable WHERE course_section_projection.source_version < EXCLUDED.source_version")
      .param("id",p.courseSectionId()).param("period",p.academicPeriodId()).param("status",p.status())
      .param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).param("reliable",p.reliable()).update();
  }

  @Override public void upsertStudentCourse(StudentCourseSectionProjection p) {
    jdbc.sql("INSERT INTO student_course_section_projection (academic_profile_id,student_account_id,course_section_id,academic_period_id,enrolled_at,removed_at,source_version,updated_at,reliable) VALUES (:profile,:student,:course,:period,:enrolled,:removed,:version,:updated,:reliable) ON CONFLICT (academic_profile_id,course_section_id) DO UPDATE SET student_account_id=EXCLUDED.student_account_id,academic_period_id=EXCLUDED.academic_period_id,enrolled_at=EXCLUDED.enrolled_at,removed_at=EXCLUDED.removed_at,source_version=EXCLUDED.source_version,updated_at=EXCLUDED.updated_at,reliable=EXCLUDED.reliable WHERE student_course_section_projection.source_version < EXCLUDED.source_version")
      .param("profile",p.academicProfileId()).param("student",p.studentAccountId()).param("course",p.courseSectionId())
      .param("period",p.academicPeriodId()).param("enrolled",Timestamps.toTimestamp(p.enrolledAt())).param("removed",Timestamps.toTimestamp(p.removedAt()))
      .param("version",p.sourceVersion()).param("updated",Timestamps.toTimestamp(p.updatedAt())).param("reliable",p.reliable()).update();
  }

  @Override public Optional<AcademicPeriodProjection> findPeriod(UUID id) {
    return jdbc.sql("SELECT " + PERIOD_COLUMNS + " FROM academic_period_projection WHERE academic_period_id=:id")
      .param("id",id).query(JdbcAcademicProjectionRepository::mapPeriod).optional();
  }
  @Override public Optional<AcademicPeriodProjection> findActivePeriod() {
    return jdbc.sql("SELECT " + PERIOD_COLUMNS + " FROM academic_period_projection WHERE status='ACTIVE' ORDER BY updated_at DESC LIMIT 1")
      .query(JdbcAcademicProjectionRepository::mapPeriod).optional();
  }
  @Override public Optional<StudentAcademicProfileProjection> findStudentProfile(UUID studentAccountId) {
    return jdbc.sql("SELECT " + PROFILE_COLUMNS + " FROM student_academic_profile_projection WHERE student_account_id=:student")
      .param("student",studentAccountId).query(JdbcAcademicProjectionRepository::mapProfile).optional();
  }
  @Override public Optional<CourseSectionProjection> findCourseSection(UUID id) {
    return jdbc.sql("SELECT " + SECTION_COLUMNS + " FROM course_section_projection WHERE course_section_id=:id")
      .param("id",id).query(JdbcAcademicProjectionRepository::mapSection).optional();
  }
  @Override public Optional<StudentCourseSectionProjection> findStudentCourse(UUID studentAccountId, UUID courseSectionId) {
    return jdbc.sql("SELECT " + COURSE_COLUMNS + " FROM student_course_section_projection WHERE student_account_id=:student AND course_section_id=:course")
      .param("student",studentAccountId).param("course",courseSectionId).query(JdbcAcademicProjectionRepository::mapCourse).optional();
  }

  @Override public boolean reliable() {
    return jdbc.sql("SELECT COALESCE(bool_and(reliable),true) FROM (SELECT reliable FROM academic_period_projection UNION ALL SELECT reliable FROM student_academic_profile_projection UNION ALL SELECT reliable FROM course_section_projection UNION ALL SELECT reliable FROM student_course_section_projection) p")
      .query(Boolean.class).single();
  }

  private static AcademicPeriodProjection mapPeriod(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new AcademicPeriodProjection(rs.getObject("academic_period_id",UUID.class),rs.getString("code"),rs.getString("status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"),rs.getBoolean("reliable"));
  }
  private static StudentAcademicProfileProjection mapProfile(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new StudentAcademicProfileProjection(rs.getObject("academic_profile_id",UUID.class),rs.getObject("student_account_id",UUID.class),rs.getInt("current_semester"),rs.getString("academic_status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"),rs.getBoolean("reliable"));
  }
  private static CourseSectionProjection mapSection(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new CourseSectionProjection(rs.getObject("course_section_id",UUID.class),rs.getObject("academic_period_id",UUID.class),rs.getString("status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"),rs.getBoolean("reliable"));
  }
  private static StudentCourseSectionProjection mapCourse(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new StudentCourseSectionProjection(rs.getObject("academic_profile_id",UUID.class),rs.getObject("student_account_id",UUID.class),rs.getObject("course_section_id",UUID.class),rs.getObject("academic_period_id",UUID.class),Timestamps.toInstant(rs,"enrolled_at"),Timestamps.toInstant(rs,"removed_at"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"),rs.getBoolean("reliable"));
  }
}
