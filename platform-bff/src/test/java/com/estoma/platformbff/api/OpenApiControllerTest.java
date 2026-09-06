package com.estoma.platformbff.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

@SpringBootTest
@ActiveProfiles("test")
class OpenApiControllerTest {
  private static final Path VERSIONED_DOCUMENT = Path.of("docs/openapi.yaml");
  private static final ObjectMapper JSON = new ObjectMapper()
      .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
  @Autowired OpenApiController openApi;
  @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

  @Test
  @SuppressWarnings("unchecked")
  void documentHasEveryLivePublicRouteWithConcreteSchemasAndAvailabilityProvider() {
    Map<String, Object> document = openApi.document();
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");

    assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(documentedMappings());
    assertThat(paths).containsKey("/api/v1/wash/appointments/availability");

    Map<String, Object> availability = operation(paths,
        "/api/v1/wash/appointments/availability", "get");
    assertThat((java.util.List<Map<String, Object>>) availability.get("parameters"))
        .allSatisfy(parameter -> assertThat(parameter).containsEntry("required", true))
        .anySatisfy(parameter -> {
          assertThat(parameter).containsEntry("name", "instrumentCount");
          assertThat((Map<String, Object>) parameter.get("schema"))
              .containsEntry("minimum", 1L).containsEntry("maximum", 80L);
        });
    Map<String, Object> availabilitySchema = responseSchema(availability, "200");
    assertThat((java.util.List<String>) availabilitySchema.get("required"))
        .containsExactlyInAnyOrder("canSchedule", "blockingReasons", "dailyPenaltyPoints",
            "dailyCompletedAppointments", "exceptionalAuthorizationRequired",
            "exceptionalAuthorizationAvailable", "exceptionalAuthorizationId",
            "availableTimeSlots");
    Map<String, Object> availabilityProperties =
        (Map<String, Object>) availabilitySchema.get("properties");
    assertThat((Map<String, Object>) availabilityProperties.get("blockingReasons"))
        .containsEntry("type", "array");
    assertThat((Map<String, Object>) availabilityProperties.get("availableTimeSlots"))
        .containsEntry("type", "array");
    Map<String, Object> slot = (Map<String, Object>) ((Map<String, Object>)
        availabilityProperties.get("availableTimeSlots")).get("items");
    assertThat((java.util.List<String>) slot.get("required"))
        .containsExactlyInAnyOrder("appointmentTimeSlotId", "startsAt", "endsAt",
            "availableCapacity", "bookingDeadlineAt");
    Map<String, Object> unavailable = (Map<String, Object>)
        ((Map<String, Object>) availability.get("responses")).get("503");
    assertThat((Map<String, Object>) unavailable.get("headers")).containsKey("Retry-After");

    Map<String, Object> formContext = operation(paths, "/api/v1/wash/appointments/form-context", "get");
    Map<String, Object> responseSchema = responseSchema(formContext, "200");
    assertThat(responseSchema).containsEntry("type", "object");
    assertThat((Map<String, Object>) responseSchema.get("properties"))
        .containsKeys("student", "availableCourseSections");
    Map<String, Object> student = (Map<String, Object>) ((Map<String, Object>) responseSchema.get("properties"))
        .get("student");
    assertThat((java.util.List<String>) student.get("type")).containsExactly("object", "null");

    Map<String, Object> home = operation(paths, "/api/v1/wash/student/home", "get");
    Map<String, Object> homeSchema = responseSchema(home, "200");
    Map<String, Object> appointment = (Map<String, Object>) ((Map<String, Object>) homeSchema.get("properties"))
        .get("appointment");
    Map<String, Object> appointmentProperties = (Map<String, Object>) appointment.get("properties");
    assertThat(appointmentProperties)
        .containsKeys("appointmentVersion", "usesExceptionalAuthorization", "studentCancellationAction");
    assertThat((java.util.List<String>) appointment.get("required"))
        .contains("appointmentVersion", "usesExceptionalAuthorization", "studentCancellationAction");
    assertThat((java.util.List<String>) ((Map<String, Object>) appointmentProperties
        .get("studentCancellationAction")).get("enum"))
        .containsExactly("AVAILABLE", "DEADLINE_PASSED", "NOT_APPLICABLE");
    Map<String, Object> timeSlot = (Map<String, Object>) appointmentProperties.get("timeSlot");
    assertThat((Map<String, Object>) timeSlot.get("properties")).containsKey("cancellationDeadlineAt");
    Map<String, Object> washExecution = (Map<String, Object>) appointmentProperties.get("washExecution");
    assertThat((Map<String, Object>) washExecution.get("properties")).containsKeys(
        "version", "executionVersion", "exitSubmittedAt", "submittedExitMaterials", "completedAt",
        "finalExitMaterials", "activeResourceAssignment", "lastResourceAssignment");
    assertThat((java.util.List<String>) washExecution.get("required"))
        .contains("version", "executionVersion");

    Map<String, Object> admin = operation(paths, "/api/v1/admin/wash/calendars", "post");
    assertThat((java.util.List<Map<String, Object>>) admin.get("parameters"))
        .anySatisfy(parameter -> assertThat(parameter).containsEntry("name", "Idempotency-Key"));
    assertThat(responseSchema(admin, "202"))
        .containsEntry("$ref", "#/components/schemas/AcceptedOperation");

    assertThat(paths).doesNotContainKey("/api/v1/wash/supervision/entry-lookup");
    Map<String, Object> supervisorHome = operation(paths, "/api/v1/wash/supervision/home", "get");
    Map<String, Object> supervisorHomeSchema = responseSchema(supervisorHome, "200");
    assertThat((java.util.List<String>) supervisorHomeSchema.get("required"))
        .containsExactlyInAnyOrder("serviceDate", "summary", "pendingReassignmentsCount");
    Map<String, Object> summary = (Map<String, Object>)
        ((Map<String, Object>) supervisorHomeSchema.get("properties")).get("summary");
    assertThat((java.util.List<String>) summary.get("required"))
        .containsExactlyInAnyOrder("registeredAppointments", "inProcessAppointments",
            "completedAppointments", "deniedAppointments", "cancelledAppointments");

    Map<String, Object> lookup = operation(paths, "/api/v1/wash/supervision/lookup", "post");
    assertThat(lookup).doesNotContainKey("parameters");
    Map<String, Object> lookupRequest = (Map<String, Object>) ((Map<String, Object>)
        ((Map<String, Object>) ((Map<String, Object>) lookup.get("requestBody")).get("content"))
            .get("application/json")).get("schema");
    assertThat((java.util.List<String>) lookupRequest.get("required")).contains("lookupType");
    assertThat((java.util.List<String>) ((Map<String, Object>)
        ((Map<String, Object>) lookupRequest.get("properties")).get("lookupType")).get("enum"))
        .containsExactly("QR", "STUDENT_ENROLLMENT");
    assertThat(((Map<String, Object>) lookup.get("responses"))).containsKey("200");
    assertThat((Map<String, Boolean>) lookup.get("x-provider-ready"))
        .containsEntry("STUDENT_ENROLLMENT", true).containsEntry("QR", true);
    assertThat((Map<String, Boolean>) lookup.get("x-provider-ready-by-default"))
        .containsEntry("STUDENT_ENROLLMENT", true).containsEntry("QR", false);
    assertThat(lookup).containsEntry("x-qr-feature-flag", "BFF_WASH_QR_ENABLED");
    Map<String, Object> lookupSchema = responseSchema(lookup, "200");
    assertThat((java.util.List<String>) lookupSchema.get("required"))
        .containsExactlyInAnyOrder("serviceDate", "nextAction", "student", "appointment");
    Map<String, Object> nextAction = (Map<String, Object>)
        ((Map<String, Object>) lookupSchema.get("properties")).get("nextAction");
    assertThat((java.util.List<String>) nextAction.get("enum"))
        .containsExactly("ENTRY", "ENTRY_DECISION", "REASSIGNMENT", "EXIT_REVIEW", "NONE");
    Map<String, Object> supervisorAppointment = (Map<String, Object>)
        ((Map<String, Object>) lookupSchema.get("properties")).get("appointment");
    assertThat((java.util.List<String>) supervisorAppointment.get("required"))
        .containsExactlyInAnyOrder("appointmentId", "appointmentStatus", "appointmentType",
            "instrumentCount", "pieceType", "courseSectionReference", "appointmentTimeSlot");

    Map<String, Object> reassignmentCandidates = operation(paths,
        "/api/v1/wash/supervision/pending-reassignments/{washExecutionId}/candidates", "get");
    assertThat(reassignmentCandidates).containsEntry("x-provider-ready-by-default", false);
    assertThat((Map<String, Object>) reassignmentCandidates.get("responses")).containsKey("503");
    Map<String, Object> candidatesSchema = responseSchema(reassignmentCandidates, "200");
    assertThat((java.util.List<String>) candidatesSchema.get("required"))
        .containsExactlyInAnyOrder("washExecutionId", "executionVersion", "snapshotGeneratedAt",
            "recommendedCandidate", "candidates");
    Map<String, Object> candidateProperties = (Map<String, Object>) ((Map<String, Object>)
        ((Map<String, Object>) candidatesSchema.get("properties")).get("candidates")).get("items");
    assertThat((java.util.List<String>) candidateProperties.get("required"))
        .containsExactlyInAnyOrder("cabinId", "cabinCode", "cabinName", "tankId", "tankCode",
            "tankName", "availableCapacity");

    Map<String, Object> temporaryPassword = operation(paths,
        "/api/v1/admin/accounts/{accountId}/temporary-password", "post");
    assertThat((java.util.List<Map<String, Object>>) temporaryPassword.get("parameters"))
        .anySatisfy(parameter -> assertThat(parameter).containsEntry("name", "Idempotency-Key")
            .containsEntry("in", "header").containsEntry("required", true));
    assertThat((Map<String, Object>) temporaryPassword.get("responses")).containsKey("200");

    Map<String, Object> me = operation(paths, "/api/v1/me", "get");
    Map<String, Object> meSchema = responseSchema(me, "200");
    assertThat((Map<String, Object>) meSchema.get("properties"))
        .containsKeys("accountId", "personId", "displayName", "loginType", "loginIdentifier",
            "institutionalEmail", "roleCode", "availableSystemCodes");
    assertThat((java.util.List<String>) meSchema.get("required"))
        .containsExactlyInAnyOrder("accountId", "personId", "displayName", "loginType", "loginIdentifier",
            "institutionalEmail", "roleCode", "availableSystemCodes");

    Map<String, Object> mailbox = operation(paths, "/api/v1/mailbox/messages", "get");
    assertThat((java.util.List<Map<String, Object>>) mailbox.get("parameters"))
        .anySatisfy(parameter -> assertThat(parameter).containsEntry("name", "systemCode")
            .containsEntry("in", "query").containsEntry("required", true))
        .anySatisfy(parameter -> assertThat(parameter).containsEntry("name", "limit")
            .containsEntry("in", "query").containsEntry("required", false));
  }

  @Test
  void versionedDocumentIsACompleteBidirectionalSnapshotOfTheRuntimeContract() throws Exception {
    String runtime = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(openApi.document())
        + System.lineSeparator();
    if (Boolean.getBoolean("updateOpenApi")) Files.writeString(VERSIONED_DOCUMENT, runtime);

    assertThat(Files.exists(VERSIONED_DOCUMENT)).isTrue();
    Map<String, Object> versioned = JSON.readValue(Files.readString(VERSIONED_DOCUMENT), Map.class);
    assertThat(versioned).containsEntry("openapi", "3.1.1");
    assertThat((Map<String, Object>) versioned.get("paths")).isNotEmpty();
    assertThat(JSON.readTree(Files.readString(VERSIONED_DOCUMENT))).isEqualTo(JSON.readTree(runtime));
  }

  private Set<String> documentedMappings() {
    Set<String> result = new LinkedHashSet<>();
    mappings.getHandlerMethods().forEach((mapping, handler) -> {
      for (String path : mapping.getPatternValues()) {
        if (isDocumented(path, handler)) result.add(path);
      }
    });
    return result;
  }

  private static boolean isDocumented(String path, HandlerMethod handler) {
    return handler.getBeanType().getPackageName().startsWith("com.estoma.platformbff.api")
        || path.equals("/health") || path.equals("/version") || path.equals("/actuator/health")
        || path.equals("/actuator/prometheus");
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> operation(Map<String, Object> paths, String path, String method) {
    return (Map<String, Object>) ((Map<String, Object>) paths.get(path)).get(method);
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> responseSchema(Map<String, Object> operation, String status) {
    Map<String, Object> responses = (Map<String, Object>) operation.get("responses");
    Map<String, Object> response = (Map<String, Object>) responses.get(status);
    Map<String, Object> content = (Map<String, Object>) response.get("content");
    Map<String, Object> json = (Map<String, Object>) content.get("application/json");
    return (Map<String, Object>) json.get("schema");
  }
}
