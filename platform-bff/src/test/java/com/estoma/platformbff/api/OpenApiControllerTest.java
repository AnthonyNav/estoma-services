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
  void documentHasEveryLivePublicRouteWithConcreteSchemasAndNoAvailabilityPlaceholder() {
    Map<String, Object> document = openApi.document();
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");

    assertThat(paths.keySet()).containsExactlyInAnyOrderElementsOf(documentedMappings());
    assertThat(paths).doesNotContainKey("/api/v1/wash/appointments/availability");

    Map<String, Object> formContext = operation(paths, "/api/v1/wash/appointments/form-context", "get");
    Map<String, Object> responseSchema = responseSchema(formContext, "200");
    assertThat(responseSchema).containsEntry("type", "object");
    assertThat((Map<String, Object>) responseSchema.get("properties"))
        .containsKeys("student", "availableCourseSections");
    Map<String, Object> student = (Map<String, Object>) ((Map<String, Object>) responseSchema.get("properties"))
        .get("student");
    assertThat((java.util.List<String>) student.get("type")).containsExactly("object", "null");

    Map<String, Object> admin = operation(paths, "/api/v1/admin/wash/calendars", "post");
    assertThat((java.util.List<Map<String, Object>>) admin.get("parameters"))
        .anySatisfy(parameter -> assertThat(parameter).containsEntry("name", "Idempotency-Key"));
    assertThat(responseSchema(admin, "202"))
        .containsEntry("$ref", "#/components/schemas/AcceptedOperation");

    Map<String, Object> lookup = operation(paths, "/api/v1/wash/supervision/entry-lookup", "post");
    assertThat(lookup).doesNotContainKey("parameters");
    assertThat((Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) lookup.get("requestBody"))
        .get("content")).get("application/json")).isNotNull();
    assertThat(((Map<String, Object>) lookup.get("responses"))).containsKey("200");
    assertThat((Map<String, Boolean>) lookup.get("x-provider-ready"))
        .containsEntry("STUDENT_ENROLLMENT", true).containsEntry("QR", false);

    Map<String, Object> temporaryPassword = operation(paths,
        "/api/v1/admin/accounts/{accountId}/temporary-password", "post");
    assertThat((java.util.List<Map<String, Object>>) temporaryPassword.get("parameters"))
        .anySatisfy(parameter -> assertThat(parameter).containsEntry("name", "Idempotency-Key")
            .containsEntry("in", "header").containsEntry("required", true));
    assertThat((Map<String, Object>) temporaryPassword.get("responses")).containsKey("200");
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
