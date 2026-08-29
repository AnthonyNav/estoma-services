package com.estoma.platformbff.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.platformbff.api.admin.AdminController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Keeps the committed V1 contract index independent from live controller mappings. */
class VersionedOpenApiContractTest {
  private static final Pattern ROUTE = Pattern.compile("^\\s*- \\\"([A-Z]+ /api/v1/admin/[^\\\"]+)\\\"\\s*$");

  @Test
  void committedOpenApiIndexMatchesTheAdministrativeProviderSurface() throws Exception {
    String document = Files.readString(Path.of("docs/openapi.yaml"));
    Set<String> versioned = document.lines().map(ROUTE::matcher).filter(Matcher::matches)
        .map(matcher -> matcher.group(1)).collect(Collectors.toSet());

    assertThat(versioned).containsExactlyInAnyOrderElementsOf(AdminController.routeMatrix().keySet());
  }
}
