package com.estoma.infratest;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class SystemController {

  private static final String APP_NAME = "infra-test";
  private static final String APP_VERSION = "0.2.0";
  private static final String API_KEY_HEADER = "X-API-Key";

  private final String apiKey;

  SystemController(@Value("${API_KEY:}") String apiKey) {
    this.apiKey = apiKey;
  }

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "ok", "service", APP_NAME);
  }

  @GetMapping("/version")
  Map<String, String> version(@RequestHeader(value = API_KEY_HEADER, required = false) String providedApiKey) {
    if (!StringUtils.hasText(apiKey)) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "API key is not configured");
    }

    if (!apiKey.equals(providedApiKey)) {
      throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid API key");
    }

    return Map.of("service", APP_NAME, "version", APP_VERSION);
  }
}
