package com.estoma.platformbff.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.estoma.platformbff.api.mailbox.MailboxController;
import com.estoma.platformbff.api.wash.WashController;
import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.mailbox.MailboxQueryService;
import com.estoma.platformbff.application.security.EffectiveSystemService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.application.security.AuthenticationRequiredException;
import com.estoma.platformbff.application.security.LocalAuthorizationService.AuthorizationDeniedException;
import com.estoma.platformbff.application.projection.ProjectionUnavailableException;
import com.estoma.platformbff.application.wash.WashSupervisionQueryService;
import com.estoma.platformbff.application.wash.WashSupervisionQueryService.LookupNotFoundException;
import com.estoma.platformbff.application.wash.WashSupervisionQueryService.ExecutionNotFoundException;
import com.estoma.platformbff.application.wash.WashStudentQueryService;
import com.estoma.platformbff.application.wash.AppointmentAvailabilityService;
import com.estoma.platformbff.application.wash.AppointmentAvailabilityService.AppointmentAvailabilityRefreshingException;
import com.estoma.platformbff.application.wash.ReassignmentCandidatesService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessagesResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxSystemSummaryResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse;
import com.estoma.platformbff.contracts.http.wash.ReassignmentCandidatesResponse;
import com.estoma.platformbff.contracts.http.wash.ReassignmentCandidatesResponse.CandidateResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentFormContextResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentAvailabilityResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.CourseSection;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.FormStudent;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeStudent;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.NextAction;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.AppointmentTimeSlot;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.CourseSectionReference;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorAppointment;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorHomeResponse;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorHomeSummary;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorLookupResponse;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorStudent;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorExecution;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorExecutionAppointment;
import com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.SupervisorExecutionDetailResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PublicApiMvcTest {
  private static final String KEY = "request-key";

  @Mock PublicCommandAdapter commands;
  @Mock LocalAuthorizationService authorization;
  @Mock EffectiveSystemService effectiveSystems;
  @Mock MailboxQueryService mailboxQueries;
  @Mock WashSupervisionQueryService supervision;
  @Mock WashStudentQueryService studentQueries;
  @Mock AppointmentAvailabilityService appointmentAvailability;
  @Mock ReassignmentCandidatesService reassignmentCandidates;

  private MockMvc mailboxMvc;
  private MockMvc washMvc;
  private MockMvc washUnclassifiedEnabledMvc;

  @BeforeEach
  void setUp() {
    var accepted = new AcceptedOperationResponse(UUID.randomUUID(), "PENDING", "/api/v1/operations/x",
        Instant.parse("2026-08-01T10:00:00Z"), false);
    when(commands.submit(any(), anyString(), anyString(), anyMap(), anyString(), any(), any()))
        .thenReturn(accepted);
    when(commands.submitEnriched(any(), anyString(), anyString(), anyMap(), anyMap(), anyString(),
        any(), any(), any(), any())).thenReturn(accepted);
    when(authorization.requireAuthenticatedAccount()).thenReturn(UUID.randomUUID());
    when(authorization.requireWashSupervisor())
        .thenReturn(UUID.fromString("00000000-0000-0000-0000-000000000099"));
    when(mailboxQueries.messages(anyString(), org.mockito.ArgumentMatchers.anyInt(), nullable(String.class)))
        .thenReturn(new MailboxMessagesResponse(List.of(), null));
    when(mailboxQueries.summary(anyString())).thenReturn(
        new MailboxSystemSummaryResponse("LAVADO_ULTRASONICO", 0, 0, null));
    when(supervision.pendingReassignments()).thenReturn(new PendingReassignmentsResponse(List.of()));
    when(supervision.home()).thenReturn(new SupervisorHomeResponse(LocalDate.of(2026, 8, 1),
        new SupervisorHomeSummary(12, 5, 4, 1, 0), 3));
    when(supervision.lookup(any())).thenReturn(new SupervisorLookupResponse(LocalDate.of(2026, 8, 1),
        NextAction.ENTRY, false,
        new SupervisorStudent(UUID.fromString("00000000-0000-0000-0000-000000000010"),
            "Ana García Reyes", "201945678", 7),
        new SupervisorAppointment(UUID.fromString("00000000-0000-0000-0000-000000000011"),
            "SCHEDULED", "NORMAL", 15, "HIGH_SPEED",
            new CourseSectionReference(UUID.fromString("00000000-0000-0000-0000-000000000012"),
                "12345", "Cirugía Bucal"),
            new AppointmentTimeSlot(UUID.fromString("00000000-0000-0000-0000-000000000013"),
                Instant.parse("2026-08-01T10:00:00Z"), Instant.parse("2026-08-01T11:00:00Z"),
                "America/Mexico_City")),
        null, null, new com.estoma.platformbff.contracts.http.wash.WashSupervisorResponses.ArrivalEligibility(
            "OPEN", Instant.parse("2026-08-01T10:00:00Z"), Instant.parse("2026-08-01T09:45:00Z"),
            Instant.parse("2026-08-01T10:10:00Z"))));
    UUID exactExecutionId = UUID.fromString("00000000-0000-0000-0000-000000000020");
    when(supervision.execution(exactExecutionId)).thenReturn(new SupervisorExecutionDetailResponse(
        LocalDate.of(2026, 7, 31), false,
        new SupervisorStudent(UUID.fromString("00000000-0000-0000-0000-000000000010"),
            "Ana García Reyes", "201945678", 7),
        new SupervisorExecutionAppointment(
            UUID.fromString("00000000-0000-0000-0000-000000000011"), 5L, "COMPLETED",
            "NORMAL", 15, "HIGH_SPEED", null,
            new AppointmentTimeSlot(UUID.fromString("00000000-0000-0000-0000-000000000013"),
                Instant.parse("2026-07-31T14:00:00Z"), Instant.parse("2026-07-31T15:00:00Z"),
                "America/Mexico_City")),
        new SupervisorExecution(exactExecutionId, "COMPLETED", 6L, 6L,
            Instant.parse("2026-07-31T13:55:00Z"), null,
            Instant.parse("2026-07-31T14:40:00Z"), null,
            Instant.parse("2026-07-31T14:45:00Z"), null, null, null)));
    Clock clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), java.time.ZoneOffset.UTC);
    mailboxMvc = MockMvcBuilders.standaloneSetup(
        new MailboxController(mailboxQueries, authorization, effectiveSystems, commands, clock))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    washMvc = MockMvcBuilders.standaloneSetup(
        new WashController(commands, authorization, supervision, studentQueries,
            appointmentAvailability, reassignmentCandidates, clock, false))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    washUnclassifiedEnabledMvc = MockMvcBuilders.standaloneSetup(
        new WashController(commands, authorization, supervision, studentQueries,
            appointmentAvailability, reassignmentCandidates, clock, true))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void mailboxEndpointsExposeOnlyTheDocumentedSurfaceAndRequireIdempotencyForWrites() throws Exception {
    mailboxMvc.perform(get("/api/v1/mailbox/messages").param("systemCode", "LAVADO_ULTRASONICO"))
        .andExpect(status().isOk());
    mailboxMvc.perform(get("/api/v1/mailbox/summary").param("systemCode", "LAVADO_ULTRASONICO"))
        .andExpect(status().isOk());
    mailboxMvc.perform(post("/api/v1/mailbox/visits")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"systemCode\":\"LAVADO_ULTRASONICO\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    mailboxMvc.perform(post("/api/v1/mailbox/visits").header("Idempotency-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"systemCode\":\"LAVADO_ULTRASONICO\"}"))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.pollPath").value("/api/v1/operations/x"))
        .andExpect(jsonPath("$.submittedAt").value("2026-08-01T10:00:00Z"))
        .andExpect(jsonPath("$.operationUrl").doesNotExist())
        .andExpect(jsonPath("$.acceptedAt").doesNotExist());
  }

  @Test
  void everyAllowedWashWriteAcknowledgesTheSingleDurableOperation() throws Exception {
    assertAccepted(post("/api/v1/wash/appointments"),
        "{\"appointmentTimeSlotId\":\"00000000-0000-0000-0000-000000000001\",\"courseSectionId\":\"00000000-0000-0000-0000-000000000002\",\"appointmentType\":\"NORMAL\",\"instrumentCount\":1,\"pieceType\":\"HIGH_SPEED\",\"regulationAccepted\":true}");
    assertAccepted(post("/api/v1/wash/appointments/00000000-0000-0000-0000-000000000001/cancel"),
        "{\"expectedVersion\":1}");
    assertAccepted(post("/api/v1/wash/exceptional-authorizations"),
        "{\"studentAccountId\":\"00000000-0000-0000-0000-000000000001\",\"serviceDate\":\"2026-08-01\",\"reason\":\"Clinical exception\"}");
    assertAccepted(post("/api/v1/wash/exceptional-authorizations/00000000-0000-0000-0000-000000000001/cancel"),
        "{\"reason\":\"No longer required\"}");
    assertAccepted(post("/api/v1/wash/executions/arrivals"),
        "{\"appointmentId\":\"00000000-0000-0000-0000-000000000001\"}");
    assertAccepted(post("/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/entry-decision"),
        "{\"identityConfirmed\":true,\"requirementsSatisfied\":true,\"decision\":\"AUTHORIZED\",\"expectedVersion\":1}");
    assertAccepted(post("/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/reassignment"),
        "{\"cabinId\":\"00000000-0000-0000-0000-000000000002\",\"tankId\":\"00000000-0000-0000-0000-000000000003\",\"expectedVersion\":1}");
    assertAccepted(post("/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/exit"),
        materials("materials"));
    assertAccepted(post("/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/complete"),
        materials("finalMaterials"));
    assertAccepted(post("/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/clinic-cancel"),
        "{\"cancellationSubreason\":\"NO_ALTERNATIVE\",\"expectedVersion\":1}");
    assertAccepted(post("/api/v1/wash/operational-resources/disable"),
        "{\"cabinId\":\"00000000-0000-0000-0000-000000000001\",\"causeType\":\"FAILURE\",\"reason\":\"Maintenance\"}");
    assertAccepted(post("/api/v1/wash/operational-resources/restore"),
        "{\"resourceUnavailabilityId\":\"00000000-0000-0000-0000-000000000001\",\"expectedVersion\":1}");

    verifyEnrichedOwnerCommand("wash-schedule-appointment", "ScheduleAppointment");
    verifyOwnerCommand("wash-cancel-appointment", "CancelAppointment");
    verifyEnrichedOwnerCommand("wash-grant-exceptional-authorization", "GrantExceptionalAppointmentAuthorization");
    verifyOwnerCommand("wash-cancel-exceptional-authorization", "CancelExceptionalAppointmentAuthorization");
    verifyEnrichedOwnerCommand("wash-register-arrival", "RegisterWashArrival");
    verifyEnrichedOwnerCommand("wash-decide-entry", "DecideWashEntry");
    verifyEnrichedOwnerCommand("wash-resolve-reassignment", "ResolvePendingReassignment");
    verifyEnrichedOwnerCommand("wash-submit-exit", "SubmitWashExit");
    verifyEnrichedOwnerCommand("wash-complete-execution", "CompleteWashExecution");
    verifyEnrichedOwnerCommand("wash-clinic-cancel-execution", "CancelWashExecutionByClinic");
    verifyEnrichedOwnerCommand("wash-disable-operational-resource", "DisableOperationalResource");
    verifyEnrichedOwnerCommand("wash-restore-operational-resource", "RestoreOperationalResource");
  }

  @Test
  void scheduleAppointmentRequiresExplicitRegulationAcceptance() throws Exception {
    washMvc.perform(post("/api/v1/wash/appointments").header("Idempotency-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"appointmentTimeSlotId\":\"00000000-0000-0000-0000-000000000001\",\"courseSectionId\":\"00000000-0000-0000-0000-000000000002\",\"appointmentType\":\"NORMAL\",\"instrumentCount\":1,\"pieceType\":\"HIGH_SPEED\",\"regulationAccepted\":false}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unclassifiedEntryRejectionIsUnavailableBeforeCreatingAnOperationWhenGateIsOff()
      throws Exception {
    washMvc.perform(post("/api/v1/wash/executions/{id}/entry-decision",
            "00000000-0000-0000-0000-000000000001")
            .header("Idempotency-Key", "unclassified-off")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"decision\":\"REJECTED\",\"rejectionReason\":\"Motivo operativo\","
                + "\"expectedVersion\":1}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code")
            .value("BFF.WASH_UNCLASSIFIED_ENTRY_REJECTION_UNAVAILABLE"));

    verify(authorization).requireWashSupervisor();
    verifyNoInteractions(commands);
  }

  @Test
  void authorizationPrecedesTheUnclassifiedEntryFeatureGate() throws Exception {
    when(authorization.requireWashSupervisor()).thenThrow(new AuthorizationDeniedException());

    washMvc.perform(post("/api/v1/wash/executions/{id}/entry-decision",
            "00000000-0000-0000-0000-000000000001")
            .header("Idempotency-Key", "unclassified-forbidden")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"decision\":\"REJECTED\",\"rejectionReason\":\"Motivo operativo\","
                + "\"expectedVersion\":1}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("BFF.ACCESS_DENIED"));

    verifyNoInteractions(commands);
  }

  @Test
  void authenticationPrecedesTheUnclassifiedEntryFeatureGate() throws Exception {
    when(authorization.requireWashSupervisor()).thenThrow(new AuthenticationRequiredException());

    washMvc.perform(post("/api/v1/wash/executions/{id}/entry-decision",
            "00000000-0000-0000-0000-000000000001")
            .header("Idempotency-Key", "unclassified-unauthenticated")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"decision\":\"REJECTED\",\"rejectionReason\":\"Motivo operativo\","
                + "\"expectedVersion\":1}"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("BFF.AUTHENTICATION_REQUIRED"));

    verifyNoInteractions(commands);
  }

  @Test
  void omissionAndExplicitNullChecksProduceTheSameOwnerAndIdempotencyPayloadShape()
      throws Exception {
    String path = "/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/entry-decision";
    String omission = "{\"decision\":\"REJECTED\",\"rejectionReason\":\"Motivo operativo\","
        + "\"expectedVersion\":1}";
    String explicitNull = "{\"identityConfirmed\":null,\"requirementsSatisfied\":null,"
        + "\"decision\":\"REJECTED\",\"rejectionReason\":\"Motivo operativo\","
        + "\"expectedVersion\":1}";

    washUnclassifiedEnabledMvc.perform(post(path).header("Idempotency-Key", "omitted")
            .contentType(MediaType.APPLICATION_JSON).content(omission))
        .andExpect(status().isAccepted());
    washUnclassifiedEnabledMvc.perform(post(path).header("Idempotency-Key", "explicit-null")
            .contentType(MediaType.APPLICATION_JSON).content(explicitNull))
        .andExpect(status().isAccepted());

    for (String key : List.of("omitted", "explicit-null")) {
      verify(commands).submitEnriched(any(), eq("wash-decide-entry"), eq("DecideWashEntry"),
          argThat(PublicApiMvcTest::isCanonicalUnclassifiedEntryPayload),
          argThat(PublicApiMvcTest::isCanonicalUnclassifiedEntryIntent), eq(key), any(), eq(1),
          any(), eq(1));
    }
  }

  @Test
  void legacyRejectedChecksRemainPresentAndInvalidDecisionShapesNeverReachTheAdapter()
      throws Exception {
    String path = "/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/entry-decision";
    washMvc.perform(post(path).header("Idempotency-Key", "legacy-false")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"identityConfirmed\":false,\"requirementsSatisfied\":false,"
                + "\"decision\":\"REJECTED\",\"rejectionReason\":\"Motivo clasificado\","
                + "\"expectedVersion\":1}"))
        .andExpect(status().isAccepted());
    verify(commands).submitEnriched(any(), eq("wash-decide-entry"), eq("DecideWashEntry"),
        argThat(payload -> Boolean.FALSE.equals(payload.get("identityConfirmed"))
            && Boolean.FALSE.equals(payload.get("requirementsSatisfied"))),
        argThat(payload -> Boolean.FALSE.equals(payload.get("identityConfirmed"))
            && Boolean.FALSE.equals(payload.get("requirementsSatisfied"))),
        eq("legacy-false"), any(), eq(1), any(), eq(1));
  }

  @Test
  void invalidEntryDecisionCombinationsAndNonBooleanChecksReturn400WithoutAnOperation()
      throws Exception {
    String path = "/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/entry-decision";
    for (String body : List.of(
        "{\"identityConfirmed\":false,\"decision\":\"REJECTED\","
            + "\"rejectionReason\":\"Parcial\",\"expectedVersion\":1}",
        "{\"identityConfirmed\":null,\"requirementsSatisfied\":true,"
            + "\"decision\":\"REJECTED\",\"rejectionReason\":\"Parcial\","
            + "\"expectedVersion\":1}",
        "{\"decision\":\"AUTHORIZED\",\"expectedVersion\":1}",
        "{\"identityConfirmed\":false,\"requirementsSatisfied\":true,"
            + "\"decision\":\"AUTHORIZED\",\"expectedVersion\":1}",
        "{\"identityConfirmed\":true,\"requirementsSatisfied\":true,"
            + "\"decision\":\"AUTHORIZED\",\"rejectionReason\":\"No aplica\","
            + "\"expectedVersion\":1}",
        "{\"identityConfirmed\":false,\"requirementsSatisfied\":false,"
            + "\"decision\":\"REJECTED\",\"expectedVersion\":1}",
        "{\"identityConfirmed\":\"false\",\"requirementsSatisfied\":false,"
            + "\"decision\":\"REJECTED\",\"rejectionReason\":\"Tipo inválido\","
            + "\"expectedVersion\":1}",
        "{\"identityConfirmed\":0,\"requirementsSatisfied\":false,"
            + "\"decision\":\"REJECTED\",\"rejectionReason\":\"Tipo inválido\","
            + "\"expectedVersion\":1}",
        "{\"identityConfirmed\":false,\"requirementsSatisfied\":false,"
            + "\"decision\":\"REJECTED\",\"rejectionReason\":\"" + "x".repeat(501)
            + "\",\"expectedVersion\":1}")) {
      washUnclassifiedEnabledMvc.perform(post(path).header("Idempotency-Key", "invalid")
              .contentType(MediaType.APPLICATION_JSON).content(body))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("BFF.INVALID_REQUEST"));
    }
    verifyNoInteractions(commands);
  }

  @Test
  void exitMaterialCountersMustBeCompleteStrictNonnegativeInt32Values() throws Exception {
    String execution = "/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/";
    for (String operation : List.of("exit", "complete")) {
      String property = operation.equals("exit") ? "materials" : "finalMaterials";
      for (String counters : List.of(
          "\"packageCount\":1,\"greenPaperCassette8Count\":0,\"greenPaperCassette10Count\":0",
          "\"packageCount\":null,\"greenPaperCassette8Count\":0,\"greenPaperCassette10Count\":0,\"witnessTapePortionCount\":0",
          "\"packageCount\":\"1\",\"greenPaperCassette8Count\":0,\"greenPaperCassette10Count\":0,\"witnessTapePortionCount\":0",
          "\"packageCount\":1.0,\"greenPaperCassette8Count\":0,\"greenPaperCassette10Count\":0,\"witnessTapePortionCount\":0",
          "\"packageCount\":2147483648,\"greenPaperCassette8Count\":0,\"greenPaperCassette10Count\":0,\"witnessTapePortionCount\":0",
          "\"packageCount\":-1,\"greenPaperCassette8Count\":0,\"greenPaperCassette10Count\":0,\"witnessTapePortionCount\":0",
          "\"packageCount\":0,\"greenPaperCassette8Count\":0,\"greenPaperCassette10Count\":0,\"witnessTapePortionCount\":0")) {
        washMvc.perform(post(execution + operation).header("Idempotency-Key", "strict-materials")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"" + property + "\":{" + counters + "},\"expectedVersion\":1}"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("BFF.INVALID_REQUEST"));
      }
    }
    verifyNoInteractions(commands);
  }

  @Test
  void exitMaterialCountersAcceptTheFullInt32RangeAndPreserveCanonicalPayloads() throws Exception {
    String execution = "/api/v1/wash/executions/00000000-0000-0000-0000-000000000001/";
    String counters = "{\"packageCount\":2147483647,\"greenPaperCassette8Count\":0,"
        + "\"greenPaperCassette10Count\":0,\"witnessTapePortionCount\":0}";

    washMvc.perform(post(execution + "exit").header("Idempotency-Key", "strict-exit")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"materials\":" + counters + ",\"expectedVersion\":1}"))
        .andExpect(status().isAccepted());
    washMvc.perform(post(execution + "complete").header("Idempotency-Key", "strict-complete")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"finalMaterials\":" + counters + ",\"expectedVersion\":1}"))
        .andExpect(status().isAccepted());

    verify(commands).submitEnriched(any(), eq("wash-submit-exit"), eq("SubmitWashExit"),
        argThat(payload -> Integer.valueOf(Integer.MAX_VALUE).equals(
            ((Map<?, ?>) payload.get("materials")).get("packageCount"))),
        anyMap(), eq("strict-exit"), any(), eq(1), any(), eq(1));
    verify(commands).submitEnriched(any(), eq("wash-complete-execution"), eq("CompleteWashExecution"),
        argThat(payload -> Integer.valueOf(Integer.MAX_VALUE).equals(
            ((Map<?, ?>) payload.get("finalMaterials")).get("packageCount"))),
        anyMap(), eq("strict-complete"), any(), eq(1), any(), eq(1));
  }

  @Test
  void supervisionAndOwnerDerivedAppointmentAvailabilityAreAvailable() throws Exception {
    washMvc.perform(get("/api/v1/wash/supervision/pending-reassignments"))
        .andExpect(status().isOk());
    UUID executionId = UUID.fromString("00000000-0000-0000-0000-000000000020");
    var candidate = new CandidateResponse(
        UUID.fromString("00000000-0000-0000-0000-000000000021"), "107", "Cabina 107",
        UUID.fromString("00000000-0000-0000-0000-000000000022"), "B", "Tina B", 2);
    when(reassignmentCandidates.candidates(executionId)).thenReturn(
        new ReassignmentCandidatesResponse(executionId, 2,
            Instant.parse("2026-08-01T10:00:00Z"), candidate, List.of(candidate)));
    washMvc.perform(get("/api/v1/wash/supervision/pending-reassignments/{id}/candidates",
            executionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.executionVersion").value(2))
        .andExpect(jsonPath("$.recommendedCandidate.cabinCode").value("107"))
        .andExpect(jsonPath("$.candidates[0].availableCapacity").value(2));
    washMvc.perform(get("/api/v1/wash/supervision/home"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serviceDate").value("2026-08-01"))
        .andExpect(jsonPath("$.summary.registeredAppointments").value(12))
        .andExpect(jsonPath("$.summary.inProcessAppointments").value(5))
        .andExpect(jsonPath("$.summary.completedAppointments").value(4))
        .andExpect(jsonPath("$.summary.deniedAppointments").value(1))
        .andExpect(jsonPath("$.summary.cancelledAppointments").value(0))
        .andExpect(jsonPath("$.pendingReassignmentsCount").value(3));
    washMvc.perform(get("/api/v1/wash/supervision/executions/{id}", executionId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.serviceDate").value("2026-07-31"))
        .andExpect(jsonPath("$.washExecution.washExecutionId").value(executionId.toString()))
        .andExpect(jsonPath("$.washExecution.version").value(6))
        .andExpect(jsonPath("$.washExecution.executionVersion").value(6))
        .andExpect(jsonPath("$.canComplete").value(false))
        .andExpect(jsonPath("$.nextAction").doesNotExist());
    washMvc.perform(get("/api/v1/wash/supervision/executions/not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("BFF.INVALID_REQUEST"));
    washMvc.perform(post("/api/v1/wash/supervision/lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lookupType\":\"STUDENT_ENROLLMENT\",\"studentEnrollment\":\"201945678\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextAction").value("ENTRY"))
        .andExpect(jsonPath("$.canComplete").value(false))
        .andExpect(jsonPath("$.student.studentAccountId")
            .value("00000000-0000-0000-0000-000000000010"))
        .andExpect(jsonPath("$.appointment.appointmentStatus").value("SCHEDULED"));
    washMvc.perform(post("/api/v1/wash/supervision/lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lookupType\":\"QR\",\"qrRepresentation\":\"opaque-owner-value\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextAction").value("ENTRY"))
        .andExpect(jsonPath("$.appointment.appointmentStatus").value("SCHEDULED"));
    washMvc.perform(post("/api/v1/wash/supervision/lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"studentEnrollment\":\"201945678\"}"))
        .andExpect(status().isBadRequest());
    washMvc.perform(post("/api/v1/wash/supervision/lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lookupType\":\"STUDENT_ENROLLMENT\",\"studentEnrollment\":\"201945678\","
                + "\"qrRepresentation\":\"opaque\"}"))
        .andExpect(status().isBadRequest());
    washMvc.perform(post("/api/v1/wash/supervision/entry-lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lookupType\":\"STUDENT_ENROLLMENT\",\"studentEnrollment\":\"201945678\"}"))
        .andExpect(status().isNotFound());
    when(appointmentAvailability.availability(any(), eq(15), any(), any()))
        .thenReturn(new AppointmentAvailabilityResponse(true, List.of(), 0, 0,
            false, false, null, List.of()));
    washMvc.perform(get("/api/v1/wash/appointments/availability")
            .param("appointmentType", "NORMAL")
            .param("instrumentCount", "15")
            .param("pieceType", "HIGH_SPEED")
            .param("courseSectionId", "00000000-0000-0000-0000-000000000001"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.canSchedule").value(true));
  }

  @Test
  void pendingReassignmentContextFailureIsAControlledWholeListUnavailableResponse() throws Exception {
    when(supervision.pendingReassignments())
        .thenThrow(new ProjectionUnavailableException("incomplete pending reassignment"));

    washMvc.perform(get("/api/v1/wash/supervision/pending-reassignments"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("BFF.PROJECTION_UNAVAILABLE"));
  }

  @Test
  void qrLookupExposesUniformNotFoundAndProjectionUnavailableProblemsWithoutEchoingTheValue()
      throws Exception {
    when(supervision.lookup(argThat(request -> request != null
        && "unknown-opaque".equals(request.qrRepresentation()))))
        .thenThrow(new LookupNotFoundException());
    when(supervision.lookup(argThat(request -> request != null
        && "unready-opaque".equals(request.qrRepresentation()))))
        .thenThrow(new ProjectionUnavailableException("QR projection is not ready"));

    washMvc.perform(post("/api/v1/wash/supervision/lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lookupType\":\"QR\",\"qrRepresentation\":\"unknown-opaque\"}"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("BFF.WASH_ENTRY_NOT_FOUND"))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("unknown-opaque"))));
    washMvc.perform(post("/api/v1/wash/supervision/lookup")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"lookupType\":\"QR\",\"qrRepresentation\":\"unready-opaque\"}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("BFF.PROJECTION_UNAVAILABLE"))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("unready-opaque"))));
  }

  @Test
  void exactExecutionMapsAuthenticationAuthorizationNotFoundAndIncompleteProjection() throws Exception {
    UUID unauthenticated = UUID.fromString("00000000-0000-0000-0000-000000000031");
    UUID forbidden = UUID.fromString("00000000-0000-0000-0000-000000000032");
    UUID missing = UUID.fromString("00000000-0000-0000-0000-000000000033");
    UUID incomplete = UUID.fromString("00000000-0000-0000-0000-000000000034");
    when(supervision.execution(unauthenticated)).thenThrow(new AuthenticationRequiredException());
    when(supervision.execution(forbidden)).thenThrow(new AuthorizationDeniedException());
    when(supervision.execution(missing)).thenThrow(new ExecutionNotFoundException());
    when(supervision.execution(incomplete))
        .thenThrow(new ProjectionUnavailableException("incomplete exact execution projection"));

    washMvc.perform(get("/api/v1/wash/supervision/executions/{id}", unauthenticated))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("BFF.AUTHENTICATION_REQUIRED"));
    washMvc.perform(get("/api/v1/wash/supervision/executions/{id}", forbidden))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("BFF.ACCESS_DENIED"));
    washMvc.perform(get("/api/v1/wash/supervision/executions/{id}", missing))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("BFF.WASH_EXECUTION_NOT_FOUND"));
    washMvc.perform(get("/api/v1/wash/supervision/executions/{id}", incomplete))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("BFF.PROJECTION_UNAVAILABLE"));
  }

  @Test
  void appointmentAvailabilityRefreshReturns503WithRetryAfter() throws Exception {
    when(appointmentAvailability.availability(any(), eq(15), any(), any()))
        .thenThrow(new AppointmentAvailabilityRefreshingException(1));

    washMvc.perform(get("/api/v1/wash/appointments/availability")
            .param("appointmentType", "NORMAL")
            .param("instrumentCount", "15")
            .param("pieceType", "HIGH_SPEED")
            .param("courseSectionId", "00000000-0000-0000-0000-000000000001"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "1"))
        .andExpect(jsonPath("$.code").value("BFF.PROJECTION_UNAVAILABLE"));
  }

  @Test
  void studentViewsExposeTheCanonicalWASH_STU_01And_WASH_STU_02Shapes() throws Exception {
    UUID courseSectionId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    when(studentQueries.home()).thenReturn(new StudentHomeResponse(
        new StudentHomeStudent("Ana", "Ana García Reyes", "201945678", 7),
        LocalDate.of(2026, 8, 1), null));
    when(studentQueries.formContext()).thenReturn(new AppointmentFormContextResponse(
        new FormStudent("Ana García Reyes", "201945678", 7),
        List.of(new CourseSection(courseSectionId, "12345", "Cirugía"))));

    washMvc.perform(get("/api/v1/wash/student/home"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.student.firstName").value("Ana"))
        .andExpect(jsonPath("$.student.studentEnrollment").value("201945678"))
        .andExpect(jsonPath("$.serviceDate").value("2026-08-01"));
    washMvc.perform(get("/api/v1/wash/appointments/form-context"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.student.fullName").value("Ana García Reyes"))
        .andExpect(jsonPath("$.student.studentEnrollment").value("201945678"))
        .andExpect(jsonPath("$.availableCourseSections[0].courseSectionId").value(courseSectionId.toString()))
        .andExpect(jsonPath("$.student.currentSemester").value(7));
  }

  private void assertAccepted(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
      String body) throws Exception {
    washMvc.perform(request.header("Idempotency-Key", KEY).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isAccepted());
  }

  private static String materials(String property) {
    return "{\"" + property + "\":{\"packageCount\":1,\"greenPaperCassette8Count\":0,"
        + "\"greenPaperCassette10Count\":0,\"witnessTapePortionCount\":0},\"expectedVersion\":1}";
  }

  private static boolean isCanonicalUnclassifiedEntryIntent(Map<String, ?> payload) {
    return payload.size() == 4 && hasCanonicalUnclassifiedEntryFields(payload);
  }

  private static boolean hasCanonicalUnclassifiedEntryFields(Map<String, ?> payload) {
    return payload.containsKey("washExecutionId")
        && "REJECTED".equals(payload.get("decision"))
        && "Motivo operativo".equals(payload.get("rejectionReason"))
        && Integer.valueOf(1).equals(payload.get("expectedVersion"))
        && !payload.containsKey("identityConfirmed")
        && !payload.containsKey("requirementsSatisfied");
  }

  private static boolean isCanonicalUnclassifiedEntryPayload(Map<String, ?> payload) {
    return payload.size() == 6 && hasCanonicalUnclassifiedEntryFields(payload)
        && payload.containsKey("decidedByAccountId")
        && payload.containsKey("decidedAt");
  }

  private void verifyOwnerCommand(String publicOperation, String commandType) {
    verify(commands).submit(any(), eq(publicOperation), eq(commandType), anyMap(), eq(KEY), any(),
        nullable(Integer.class));
  }

  private void verifyEnrichedOwnerCommand(String publicOperation, String commandType) {
    verify(commands).submitEnriched(any(), eq(publicOperation), eq(commandType), anyMap(), anyMap(),
        eq(KEY), any(), nullable(Integer.class), any(), nullable(Integer.class));
  }
}
