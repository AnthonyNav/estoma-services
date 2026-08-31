package com.estoma.platformbff.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.estoma.platformbff.api.mailbox.MailboxController;
import com.estoma.platformbff.api.wash.WashController;
import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.mailbox.MailboxQueryService;
import com.estoma.platformbff.application.security.EffectiveSystemService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.application.wash.WashSupervisionQueryService;
import com.estoma.platformbff.application.wash.WashStudentQueryService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessagesResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxSystemSummaryResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.AppointmentFormContextResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.CourseSection;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.FormStudent;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeResponse;
import com.estoma.platformbff.contracts.http.wash.WashStudentResponses.StudentHomeStudent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
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

  private MockMvc mailboxMvc;
  private MockMvc washMvc;

  @BeforeEach
  void setUp() {
    var accepted = new AcceptedOperationResponse(UUID.randomUUID(), "PENDING", "/api/v1/operations/x",
        Instant.parse("2026-08-01T10:00:00Z"), false);
    when(commands.submit(any(), anyString(), anyString(), anyMap(), anyString(), any(), any()))
        .thenReturn(accepted);
    when(commands.submitEnriched(any(), anyString(), anyString(), anyMap(), anyMap(), anyString(),
        any(), any(), any(), any())).thenReturn(accepted);
    when(authorization.requireAuthenticatedAccount()).thenReturn(UUID.randomUUID());
    when(mailboxQueries.messages(anyString(), org.mockito.ArgumentMatchers.anyInt(), nullable(String.class)))
        .thenReturn(new MailboxMessagesResponse(List.of(), null));
    when(mailboxQueries.summary(anyString())).thenReturn(
        new MailboxSystemSummaryResponse("LAVADO_ULTRASONICO", 0, 0, null));
    when(supervision.pendingReassignments()).thenReturn(new PendingReassignmentsResponse(List.of()));
    Clock clock = Clock.fixed(Instant.parse("2026-08-01T10:00:00Z"), java.time.ZoneOffset.UTC);
    mailboxMvc = MockMvcBuilders.standaloneSetup(
        new MailboxController(mailboxQueries, authorization, effectiveSystems, commands, clock))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    washMvc = MockMvcBuilders.standaloneSetup(
        new WashController(commands, authorization, supervision, studentQueries, clock))
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
  void supervisionIsAvailableButAppointmentAvailabilityHasNoController() throws Exception {
    washMvc.perform(get("/api/v1/wash/supervision/pending-reassignments"))
        .andExpect(status().isOk());
    washMvc.perform(get("/api/v1/wash/appointments/availability"))
        .andExpect(status().isNotFound());
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

  private void verifyOwnerCommand(String publicOperation, String commandType) {
    verify(commands).submit(any(), eq(publicOperation), eq(commandType), anyMap(), eq(KEY), any(),
        nullable(Integer.class));
  }

  private void verifyEnrichedOwnerCommand(String publicOperation, String commandType) {
    verify(commands).submitEnriched(any(), eq(publicOperation), eq(commandType), anyMap(), anyMap(),
        eq(KEY), any(), nullable(Integer.class), any(), nullable(Integer.class));
  }
}
