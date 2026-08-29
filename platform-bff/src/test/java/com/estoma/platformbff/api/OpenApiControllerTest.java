package com.estoma.platformbff.api;

import static org.assertj.core.api.Assertions.assertThat;

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
        .containsKeys("studentFullName", "studentEnrollment", "currentSemester",
            "availableCourseSections");

    Map<String, Object> admin = operation(paths, "/api/v1/admin/wash/calendars", "post");
    assertThat((java.util.List<Map<String, Object>>) admin.get("parameters"))
        .anySatisfy(parameter -> assertThat(parameter).containsEntry("name", "Idempotency-Key"));
    assertThat(responseSchema(admin, "202"))
        .containsEntry("$ref", "#/components/schemas/AcceptedOperation");
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
