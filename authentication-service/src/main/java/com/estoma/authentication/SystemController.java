package com.estoma.authentication;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SystemController {

  private static final String APP_NAME = "authentication-service";
  private static final String APP_VERSION = "0.1.0";

  @GetMapping("/health")
  Map<String, String> health() {
    return healthResponse("ok");
  }

  @GetMapping("/health/startup")
  Map<String, String> startup() {
    return healthResponse("ok");
  }

  @GetMapping("/health/readiness")
  Map<String, String> readiness() {
    return healthResponse("ok");
  }

  @GetMapping("/health/liveness")
  Map<String, String> liveness() {
    return healthResponse("ok");
  }

  @GetMapping("/version")
  Map<String, String> version() {
    return Map.of("service", APP_NAME, "version", APP_VERSION);
  }

  private Map<String, String> healthResponse(String status) {
    return Map.of("status", status, "service", APP_NAME);
  }
}
