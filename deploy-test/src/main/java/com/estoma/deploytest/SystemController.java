package com.estoma.deploytest;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SystemController {

  private static final String APP_NAME = "deploy-test";
  private static final String APP_VERSION = "0.1.1";

  @GetMapping("/health")
  Map<String, String> health() {
    return Map.of("status", "ok", "service", APP_NAME);
  }

  @GetMapping("/version")
  Map<String, String> version() {
    return Map.of("service", APP_NAME, "version", APP_VERSION);
  }
}
