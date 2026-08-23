package com.estoma.appointments.infrastructure.persistence;

import com.estoma.appointments.application.port.out.AcademicProjectionRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAcademicProjectionRepository implements AcademicProjectionRepository {
  private final JdbcClient jdbc;
  public JdbcAcademicProjectionRepository(JdbcClient jdbc) { this.jdbc = jdbc; }
  @Override public void upsert(AcademicProjection p) {
    jdbc.sql("INSERT INTO student_course_section_projection (source_id,student_account_id,course_section_id,source_version,status,payload,updated_at,reliable) VALUES (:source,:student,:course,:version,:status,CAST(:payload AS jsonb),:updated,:reliable) ON CONFLICT (student_account_id,course_section_id) DO UPDATE SET source_id=EXCLUDED.source_id,source_version=EXCLUDED.source_version,status=EXCLUDED.status,payload=EXCLUDED.payload,updated_at=EXCLUDED.updated_at,reliable=EXCLUDED.reliable WHERE student_course_section_projection.source_version < EXCLUDED.source_version")
      .param("source",p.sourceId()).param("student",p.studentAccountId()).param("course",p.courseSectionId()).param("version",p.sourceVersion()).param("status",p.status()).param("payload","{}").param("updated",Timestamps.toTimestamp(p.updatedAt())).param("reliable",p.reliable()).update();
  }
  @Override public Optional<AcademicProjection> findStudentCourse(UUID student, UUID course) {
    return jdbc.sql("SELECT source_id,student_account_id,course_section_id,status,source_version,updated_at,reliable FROM student_course_section_projection WHERE student_account_id=:student AND course_section_id=:course")
      .param("student",student).param("course",course).query((rs,row)->new AcademicProjection(rs.getObject("source_id",UUID.class),rs.getObject("student_account_id",UUID.class),rs.getObject("course_section_id",UUID.class),rs.getString("status"),rs.getLong("source_version"),Timestamps.toInstant(rs,"updated_at"),rs.getBoolean("reliable"))).optional();
  }
  @Override public Optional<Instant> lastUpdated(UUID sourceId) {
    return jdbc.sql("SELECT updated_at FROM student_course_section_projection WHERE source_id=:source").param("source",sourceId).query((rs,row)->Timestamps.toInstant(rs,"updated_at")).optional();
  }
}
