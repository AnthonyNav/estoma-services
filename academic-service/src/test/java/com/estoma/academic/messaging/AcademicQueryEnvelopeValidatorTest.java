package com.estoma.academic.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.academic.contract.Actor;
import com.estoma.academic.contract.MessageEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class AcademicQueryEnvelopeValidatorTest {
  private final ObjectMapper mapper = new ObjectMapper();
  private final AcademicQueryEnvelopeValidator validator = new AcademicQueryEnvelopeValidator();

  @Test
  void acceptsEligibilitySnapshotQueryFromAuthorization() {
    assertThatCode(() -> validator.validate(query("authorization",
        "GetStudentAcademicEligibilitySnapshot", mapper.createObjectNode()
            .put("accountId", UUID.randomUUID().toString())
            .put("systemCode", "PRACTICAS_PROFESIONALES"), null)))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsAppointmentsRecoveryQueriesWithoutClientOperationId() {
    assertThatCode(() -> validator.validate(query("appointments",
        "GetAppointmentAcademicConfiguration", mapper.createObjectNode(), null)))
        .doesNotThrowAnyException();
    assertThatCode(() -> validator.validate(query("appointments",
        "ListStudentAppointmentAcademicSnapshotsPage", mapper.createObjectNode()
            .put("academicPeriodId", UUID.randomUUID().toString()).put("limit", 100), null)))
        .doesNotThrowAnyException();
  }

  @Test
  void acceptsTheActivePeriodSnapshotOnlyFromCommunications() {
    assertThatCode(() -> validator.validate(query("communications",
        "GetActiveAcademicPeriodSnapshot", mapper.createObjectNode(), null)))
        .doesNotThrowAnyException();
    assertThatThrownBy(() -> validator.validate(query("appointments",
        "GetActiveAcademicPeriodSnapshot", mapper.createObjectNode(), null)))
        .isInstanceOf(AcademicQueryEnvelopeValidator.RejectedException.class)
        .hasMessage("QUERY_PRODUCER_NOT_ALLOWED");
    assertThatThrownBy(() -> validator.validate(query("communications",
        "GetActiveAcademicPeriodSnapshot", mapper.createObjectNode().put("unexpected", true), null)))
        .isInstanceOf(AcademicQueryEnvelopeValidator.RejectedException.class)
        .hasMessage("INVALID_ACTIVE_ACADEMIC_PERIOD_QUERY");
  }

  @Test
  void keepsQueryFamiliesRequesterScoped() {
    assertThatThrownBy(() -> validator.validate(query("appointments",
        "GetStudentAcademicEligibilitySnapshot", mapper.createObjectNode()
            .put("accountId", UUID.randomUUID().toString())
            .put("systemCode", "PRACTICAS_PROFESIONALES"), null)))
        .isInstanceOf(AcademicQueryEnvelopeValidator.RejectedException.class)
        .hasMessage("QUERY_PRODUCER_NOT_ALLOWED");
    assertThatThrownBy(() -> validator.validate(query("authorization",
        "GetAppointmentAcademicConfiguration", mapper.createObjectNode(), null)))
        .isInstanceOf(AcademicQueryEnvelopeValidator.RejectedException.class)
        .hasMessage("QUERY_PRODUCER_NOT_ALLOWED");
  }

  @Test
  void rejectsAQueryThatCanTargetTwoEligibilityIdentities() {
    var payload = mapper.createObjectNode()
        .put("accountId", UUID.randomUUID().toString())
        .put("studentAcademicProfileId", UUID.randomUUID().toString())
        .put("systemCode", "PRACTICAS_PROFESIONALES");
    assertThatThrownBy(() -> validator.validate(query("authorization",
        "GetStudentAcademicEligibilitySnapshot", payload, UUID.randomUUID())))
        .isInstanceOf(AcademicQueryEnvelopeValidator.RejectedException.class)
        .hasMessage("INVALID_ELIGIBILITY_TARGET");
  }

  @Test
  void validatesCursorPeriodAndLimitForAppointmentsRecovery() {
    assertThatThrownBy(() -> validator.validate(query("appointments",
        "ListStudentAppointmentAcademicSnapshotsPage", mapper.createObjectNode().put("limit", 100), null)))
        .isInstanceOf(AcademicQueryEnvelopeValidator.RejectedException.class)
        .hasMessage("INVALID_ACADEMIC_PERIOD_ID");
    assertThatThrownBy(() -> validator.validate(query("appointments",
        "ListStudentAppointmentAcademicSnapshotsPage", mapper.createObjectNode()
            .put("academicPeriodId", UUID.randomUUID().toString()).put("limit", 0), null)))
        .isInstanceOf(AcademicQueryEnvelopeValidator.RejectedException.class)
        .hasMessage("INVALID_LIMIT");
  }

  @Test
  void isolatesUnknownQueryTypesAsContractInvalid() {
    assertThatThrownBy(() -> validator.validate(query("authorization",
        "UnknownAcademicQuery", mapper.createObjectNode(), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .isNotInstanceOf(AcademicQueryEnvelopeValidator.RejectedException.class)
        .hasMessage("unknown Academic Query");
  }

  private MessageEnvelope query(String requester, String type,
      tools.jackson.databind.JsonNode payload, UUID operationId) {
    return new MessageEnvelope(UUID.randomUUID(), type, 1, "QUERY", requester, Instant.now(),
        UUID.randomUUID(), null, operationId, null, null, null,
        new Actor("SYSTEM", null, requester), payload);
  }
}
