package com.estoma.platformbff.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.estoma.platformbff.api.admin.AdminController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Keeps the committed V1 document's administrative operations independent from controller tests. */
class VersionedOpenApiContractTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  @SuppressWarnings("unchecked")
  void committedOpenApiDocumentContainsEveryAdministrativeProviderOperation() throws Exception {
    Map<String, Object> document = JSON.readValue(Files.readString(Path.of("docs/openapi.yaml")), Map.class);
    Map<String, Object> paths = (Map<String, Object>) document.get("paths");

    assertThat(paths).isNotEmpty();
    AdminController.routeMatrix().forEach((key, route) -> {
      Map<String, Object> path = (Map<String, Object>) paths.get(route.path());
      assertThat(path).as(key).isNotNull();
      assertThat(path).containsKey(route.method().toLowerCase(Locale.ROOT));
    });
  }
}
