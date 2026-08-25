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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.estoma.platformbff.api.mailbox.MailboxController;
import com.estoma.platformbff.api.wash.WashController;
import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.mailbox.MailboxQueryService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.application.wash.WashSupervisionQueryService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxMessagesResponse;
import com.estoma.platformbff.contracts.http.mailbox.MailboxSummaryResponse;
import com.estoma.platformbff.contracts.http.wash.PendingReassignmentsResponse;
import java.time.Clock;
import java.time.Instant;
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
  @Mock MailboxQueryService mailboxQueries;
  @Mock WashSupervisionQueryService supervision;

  private MockMvc mailboxMvc;
  private MockMvc washMvc;

  @BeforeEach
  void setUp() {
    var accepted = new AcceptedOperationResponse(UUID.randomUUID(), "PENDING", "/api/v1/operations/x",
        Instant.parse("2026-08-01T10:00:00Z"), false);
    when(commands.submit(any(), anyString(), anyString(), anyMap(), anyString(), any(), any()))
        .thenReturn(accepted);
    when(authorization.requireAuthenticatedAccount()).thenReturn(UUID.randomUUID());
    when(mailboxQueries.messages()).thenReturn(new MailboxMessagesResponse(List.of()));
    when(mailboxQueries.summary()).thenReturn(new MailboxSummaryResponse(0, 0, List.of()));
    when(supervision.pendingReassignments()).thenReturn(new PendingReassignmentsResponse(List.of()));
    mailboxMvc = MockMvcBuilders.standaloneSetup(
        new MailboxController(mailboxQueries, authorization, commands))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
    washMvc = MockMvcBuilders.standaloneSetup(
        new WashController(commands, authorization, supervision, Clock.systemUTC()))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void mailboxEndpointsExposeOnlyTheDocumentedSurfaceAndRequireIdempotencyForWrites() throws Exception {
    mailboxMvc.perform(get("/api/v1/mailbox/messages"))
        .andExpect(status().isOk());
    mailboxMvc.perform(get("/api/v1/mailbox/summary"))
        .andExpect(status().isOk());
    mailboxMvc.perform(post("/api/v1/mailbox/visits")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"systemCode\":\"LAVADO_ULTRASONICO\",\"visitedAt\":\"2026-08-01T10:00:00Z\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    mailboxMvc.perform(post("/api/v1/mailbox/visits").header("Idempotency-Key", KEY)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"systemCode\":\"LAVADO_ULTRASONICO\",\"visitedAt\":\"2026-08-01T10:00:00Z\"}"))
        .andExpect(status().isAccepted());
  }

  @Test
  void everyAllowedWashWriteAcknowledgesTheSingleDurableOperation() throws Exception {
    assertAccepted(post("/api/v1/wash/appointments"),
        "{\"appointmentTimeSlotId\":\"00000000-0000-0000-0000-000000000001\",\"courseSectionId\":\"00000000-0000-0000-0000-000000000002\",\"appointmentType\":\"NORMAL\",\"instrumentCount\":1,\"pieceType\":\"HIGH_SPEED\"}");
    assertAccepted(post("/api/v1/wash/appointments/00000000-0000-0000-0000-000000000001/cancel"),
        "{\"expectedVersion\":1}");
    assertAccepted(post("/api/v1/wash/exceptional-authorizations"),
        "{\"studentAccountId\":\"00000000-0000-0000-0000-000000000001\",\"serviceDate\":\"2026-08-01\",\"reason\":\"Clinical exception\"}");
    assertAccepted(post("/api/v1/wash/exceptional-authorizations/00000000-0000-0000-0000-000000000001/cancel"),
        "{\"reason\":\"No longer required\"}");
    assertAccepted(post("/api/v1/wash/executions/arrivals"),
        "{\"appointmentId\":\"00000000-0000-0000-0000-000000000001\",\"arrivedAt\":\"2026-08-01T10:00:00Z\"}");
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

    verifyOwnerCommand("wash-schedule-appointment", "ScheduleAppointment");
    verifyOwnerCommand("wash-cancel-appointment", "CancelAppointment");
    verifyOwnerCommand("wash-grant-exceptional-authorization", "GrantExceptionalAppointmentAuthorization");
    verifyOwnerCommand("wash-cancel-exceptional-authorization", "CancelExceptionalAppointmentAuthorization");
    verifyOwnerCommand("wash-register-arrival", "RegisterWashArrival");
    verifyOwnerCommand("wash-decide-entry", "DecideWashEntry");
    verifyOwnerCommand("wash-resolve-reassignment", "ResolvePendingReassignment");
    verifyOwnerCommand("wash-submit-exit", "SubmitWashExit");
    verifyOwnerCommand("wash-complete-execution", "CompleteWashExecution");
    verifyOwnerCommand("wash-clinic-cancel-execution", "CancelWashExecutionByClinic");
    verifyOwnerCommand("wash-disable-operational-resource", "DisableOperationalResource");
    verifyOwnerCommand("wash-restore-operational-resource", "RestoreOperationalResource");
  }

  @Test
  void supervisionIsAvailableButAppointmentAvailabilityHasNoController() throws Exception {
    washMvc.perform(get("/api/v1/wash/supervision/pending-reassignments"))
        .andExpect(status().isOk());
    washMvc.perform(get("/api/v1/wash/appointments/availability"))
        .andExpect(status().isNotFound());
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
}
