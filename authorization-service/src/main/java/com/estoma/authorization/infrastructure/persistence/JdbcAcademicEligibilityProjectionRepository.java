package com.estoma.authorization.infrastructure.persistence;

import com.estoma.authorization.application.port.AcademicEligibilityProjectionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class JdbcAcademicEligibilityProjectionRepository implements AcademicEligibilityProjectionRepository {

  private final JdbcClient jdbc;

  JdbcAcademicEligibilityProjectionRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<AcademicEligibilityProjection> findByStudentProfileAndSystem(
      UUID studentAcademicProfileId, String systemCode) {
    return jdbc.sql(
            "SELECT eligibility_id, student_academic_profile_id, system_code, account_id, eligible, "
                + "policy_version, eligibility_version, academic_profile_version, pending_reason, evaluated_at, updated_at "
                + "FROM academic_eligibility_projection "
                + "WHERE student_academic_profile_id = :studentAcademicProfileId AND system_code = :systemCode")
        .param("studentAcademicProfileId", studentAcademicProfileId)
        .param("systemCode", systemCode)
        .query(AcademicEligibilityRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<AcademicEligibilityProjection> findByAccountIdAndSystemCode(
      UUID accountId, String systemCode) {
    return jdbc.sql(
            "SELECT eligibility_id, student_academic_profile_id, system_code, account_id, eligible, "
                + "policy_version, eligibility_version, academic_profile_version, pending_reason, evaluated_at, updated_at "
                + "FROM academic_eligibility_projection "
                + "WHERE account_id = :accountId AND system_code = :systemCode "
                + "ORDER BY eligibility_version DESC LIMIT 1")
        .param("accountId", accountId)
        .param("systemCode", systemCode)
        .query(AcademicEligibilityRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public int upsertIfNewer(AcademicEligibilityProjection projection) {
    return jdbc.sql(
            """
            INSERT INTO academic_eligibility_projection (
              eligibility_id, student_academic_profile_id, system_code, account_id, eligible,
              policy_version, eligibility_version, academic_profile_version, pending_reason,
              evaluated_at, updated_at
            ) VALUES (
              :eligibilityId, :studentAcademicProfileId, :systemCode, :accountId, :eligible,
              :policyVersion, :eligibilityVersion, :academicProfileVersion, :pendingReason,
              :evaluatedAt, :updatedAt
            )
            ON CONFLICT (student_academic_profile_id, system_code) DO UPDATE SET
              eligibility_id = EXCLUDED.eligibility_id,
              account_id = EXCLUDED.account_id,
              eligible = EXCLUDED.eligible,
              policy_version = EXCLUDED.policy_version,
              eligibility_version = EXCLUDED.eligibility_version,
              academic_profile_version = EXCLUDED.academic_profile_version,
              pending_reason = EXCLUDED.pending_reason,
              evaluated_at = EXCLUDED.evaluated_at,
              updated_at = EXCLUDED.updated_at
            WHERE academic_eligibility_projection.eligibility_version < EXCLUDED.eligibility_version
            """)
        .param("eligibilityId", projection.eligibilityId())
        .param("studentAcademicProfileId", projection.studentAcademicProfileId())
        .param("systemCode", projection.systemCode())
        .param("accountId", projection.accountId())
        .param("eligible", projection.eligible())
        .param("policyVersion", projection.policyVersion())
        .param("eligibilityVersion", projection.eligibilityVersion())
        .param("academicProfileVersion", projection.academicProfileVersion())
        .param("pendingReason", projection.pendingReason())
        .param("evaluatedAt", Timestamps.toTimestamp(projection.evaluatedAt()))
        .param("updatedAt", Timestamps.toTimestamp(projection.updatedAt()))
        .update();
  }

  @Override
  public int updatePendingReason(UUID studentAcademicProfileId, String systemCode, String pendingReason) {
    return jdbc.sql(
            "UPDATE academic_eligibility_projection SET pending_reason = :pendingReason, updated_at = now() "
                + "WHERE student_academic_profile_id = :studentAcademicProfileId AND system_code = :systemCode")
        .param("studentAcademicProfileId", studentAcademicProfileId)
        .param("systemCode", systemCode)
        .param("pendingReason", pendingReason)
        .update();
  }
}
