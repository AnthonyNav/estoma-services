package com.estoma.platformbff.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.estoma.platformbff.api.admin.AdminController;
import com.estoma.platformbff.application.commands.PublicCommandAdapter;
import com.estoma.platformbff.application.commands.CommandRegistry;
import com.estoma.platformbff.application.security.AuthenticationEdgeService;
import com.estoma.platformbff.application.security.LocalAuthorizationService;
import com.estoma.platformbff.contracts.http.AcceptedOperationResponse;
import java.time.Instant;
import java.util.UUID;
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
  @Mock PublicCommandAdapter commands;
  @Mock LocalAuthorizationService authorization;
  @Mock AuthenticationEdgeService authentication;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    when(authorization.requireSuperAdministrator()).thenReturn(UUID.randomUUID());
    when(authorization.requireWashAdministrator()).thenReturn(UUID.randomUUID());
    when(commands.submit(any(), any(), any(), anyMap(), any(), isNull(), isNull()))
        .thenReturn(new AcceptedOperationResponse(UUID.randomUUID(), "PENDING", "/api/v1/operations/x",
            Instant.parse("2026-08-27T12:00:00Z")));
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
  void unknownAdminRouteIsNotACommandEndpoint() throws Exception {
    mvc.perform(post("/api/v1/admin/commands")
            .header("Idempotency-Key", "key-1")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isNotFound());
  }
}
