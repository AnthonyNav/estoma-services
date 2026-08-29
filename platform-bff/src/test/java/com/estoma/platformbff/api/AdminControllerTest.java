package com.estoma.platformbff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.estoma.platformbff.api.admin.AdminController;
import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.commands.CommandRegistry;
import com.estoma.platformbff.application.security.AuthenticationEdgeService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AdminControllerTest {
  private static final UUID SUPER_ADMIN = UUID.fromString("00000000-0000-0000-0000-000000000099");
  @Mock PublicCommandAdapter commands;
  @Mock LocalAuthorizationService authorization;
  @Mock AuthenticationEdgeService authentication;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    when(authorization.requireSuperAdministrator()).thenReturn(SUPER_ADMIN);
    when(authorization.requireWashAdministrator()).thenReturn(UUID.randomUUID());
    when(commands.submit(any(), any(), any(), anyMap(), any(), isNull(), isNull()))
        .thenReturn(new AcceptedOperationResponse(UUID.randomUUID(), "PENDING", "/api/v1/operations/x",
            Instant.parse("2026-08-27T12:00:00Z")));
    when(commands.submitEnriched(any(), any(), any(), anyMap(), anyMap(), any(), isNull(), isNull(),
        isNull(), isNull())).thenReturn(new AcceptedOperationResponse(UUID.randomUUID(), "PENDING",
            "/api/v1/operations/x", Instant.parse("2026-08-27T12:00:00Z")));
    mvc = MockMvcBuilders.standaloneSetup(new AdminController(commands, authorization, authentication))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void fixedPersonRoutePublishesOnlyItsAllowlistedOwnerIntent() throws Exception {
    mvc.perform(post("/api/v1/admin/people/00000000-0000-0000-0000-000000000001/activate")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":1}"))
        .andExpect(status().isAccepted());

    verify(commands).submit(any(), eq("admin.people.activate"), eq("ActivatePerson"), anyMap(),
        eq("key-1"), isNull(), isNull());
    verify(authorization).requireSuperAdministrator();
  }

  @Test
  void fixedWashRouteRequiresWashAdministrationAccess() throws Exception {
    mvc.perform(post("/api/v1/admin/wash/calendars")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"calendarId\":\"00000000-0000-0000-0000-000000000001\",\"code\":\"2026-A\",\"name\":\"2026 A\",\"timezone\":\"America/Mexico_City\",\"effectiveFrom\":\"2026-01-01\"}"))
        .andExpect(status().isAccepted());

    verify(commands).submit(any(), eq("admin.wash.calendars.create"),
        eq("CreateServiceCalendar"), anyMap(), eq("key-1"), isNull(), isNull());
    verify(authorization).requireWashAdministrator();
  }

  @Test
  void everyDocumentedRouteMapsToOneAllowlistedCommandAndItsDeclaredOwner() {
    var registry = new CommandRegistry();

    assertThat(AdminController.routeMatrix()).hasSize(51);
    AdminController.routeMatrix().forEach((key, route) -> {
      assertThat(route.publicOperation()).startsWith("admin.");
      assertThat(registry.require(route.messageType()).logicalComponentId()).isEqualTo(route.owner());
      assertThat(route.washScoped()).isEqualTo(route.path().startsWith("/api/v1/admin/wash/"));
      assertThat(key).isEqualTo(route.method() + " " + route.path());
    });
  }

  @Test
  @SuppressWarnings({"unchecked", "rawtypes"})
  void academicRoutesDeriveRequiredOwnerActorFieldsWithoutChangingClientIntent() throws Exception {
    UUID profileId = UUID.randomUUID();
    UUID periodId = UUID.randomUUID();
    UUID sectionId = UUID.randomUUID();
    UUID enrollmentId = UUID.randomUUID();
    UUID importId = UUID.randomUUID();

    mvc.perform(patch("/api/v1/admin/academic/students/{id}/student-number", profileId)
            .header("Idempotency-Key", "correct-number").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":1,\"currentStudentNumber\":\"A1\",\"newStudentNumber\":\"A2\",\"reason\":\"typo\"}"))
        .andExpect(status().isAccepted());
    mvc.perform(post("/api/v1/admin/academic/periods").header("Idempotency-Key", "period")
            .contentType(MediaType.APPLICATION_JSON).content("{\"academicPeriodId\":\"" + periodId
                + "\",\"code\":\"2026-1\",\"name\":\"Spring\",\"startDate\":\"2026-01-01\",\"endDate\":\"2026-06-30\",\"warningAcknowledged\":true}"))
        .andExpect(status().isAccepted());
    mvc.perform(patch("/api/v1/admin/academic/course-sections/{id}", sectionId)
            .header("Idempotency-Key", "section-update").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":1,\"name\":\"Endodontics\"}"))
        .andExpect(status().isAccepted());
    mvc.perform(post("/api/v1/admin/academic/course-sections/{id}/activate", sectionId)
            .header("Idempotency-Key", "section-activate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":1,\"academicPeriodId\":\"" + periodId + "\",\"name\":\"Endodontics\"}"))
        .andExpect(status().isAccepted());
    mvc.perform(post("/api/v1/admin/academic/course-sections/{id}/deactivate", sectionId)
            .header("Idempotency-Key", "section-deactivate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":2,\"academicPeriodId\":\"" + periodId + "\"}"))
        .andExpect(status().isAccepted());
    mvc.perform(post("/api/v1/admin/academic/enrollments/{id}/reactivate", enrollmentId)
            .header("Idempotency-Key", "enrollment-reactivate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"studentAcademicProfileId\":\"" + profileId + "\",\"courseSectionId\":\"" + sectionId
                + "\",\"expectedVersion\":1}"))
        .andExpect(status().isAccepted());
    mvc.perform(post("/api/v1/admin/academic/imports").header("Idempotency-Key", "import-start")
            .contentType(MediaType.APPLICATION_JSON).content("{\"importId\":\"" + importId + "\",\"fileReference\":\""
                + UUID.randomUUID() + "\",\"fileChecksum\":\"checksum\"}"))
        .andExpect(status().isAccepted());
    mvc.perform(post("/api/v1/admin/academic/imports/{id}/resume", importId)
            .header("Idempotency-Key", "import-resume").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isAccepted());

    ArgumentCaptor<String> messageTypes = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map> ownerPayloads = ArgumentCaptor.forClass(Map.class);
    ArgumentCaptor<Map> idempotencyPayloads = ArgumentCaptor.forClass(Map.class);
    verify(commands, times(8)).submitEnriched(eq(SUPER_ADMIN), any(), messageTypes.capture(),
        ownerPayloads.capture(), idempotencyPayloads.capture(), any(), isNull(), isNull(), isNull(), isNull());

    Map<String, String> expectedActorFields = Map.of(
        "CorrectStudentNumber", "correctedByAccountId",
        "CreateAndActivateAcademicPeriod", "requestedByAccountId",
        "UpdateCourseSection", "requestedByAccountId",
        "ActivateCourseSection", "requestedByAccountId",
        "DeactivateCourseSection", "requestedByAccountId",
        "ReactivateStudentEnrollment", "requestedByAccountId",
        "StartStudentImport", "requestedByAccountId",
        "ResumeStudentImport", "requestedByAccountId");
    Map<String, List<Map>> payloadsByType = new LinkedHashMap<>();
    for (int index = 0; index < messageTypes.getAllValues().size(); index++) {
      payloadsByType.put(messageTypes.getAllValues().get(index), List.of(
          ownerPayloads.getAllValues().get(index), idempotencyPayloads.getAllValues().get(index)));
    }
    assertThat(payloadsByType).hasSize(expectedActorFields.size()).containsOnlyKeys(expectedActorFields.keySet());
    expectedActorFields.forEach((messageType, actorField) -> {
      List<Map> payloads = payloadsByType.get(messageType);
      assertThat(payloads.getFirst()).containsEntry(actorField, SUPER_ADMIN);
      assertThat(payloads.get(1)).doesNotContainKey(actorField);
    });
  }

  @Test
  void serverDerivedAcademicActorFieldsAreRejectedWhenSuppliedByTheClient() throws Exception {
    mvc.perform(patch("/api/v1/admin/academic/students/{id}/student-number", UUID.randomUUID())
            .header("Idempotency-Key", "reject-client-actor").contentType(MediaType.APPLICATION_JSON)
            .content("{\"expectedVersion\":1,\"currentStudentNumber\":\"A1\",\"newStudentNumber\":\"A2\",\"reason\":\"typo\",\"correctedByAccountId\":\""
                + UUID.randomUUID() + "\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void unknownAdminRouteIsNotACommandEndpoint() throws Exception {
    mvc.perform(post("/api/v1/admin/commands")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());
  }
}
