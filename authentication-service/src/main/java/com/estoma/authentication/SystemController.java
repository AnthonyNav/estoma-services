package com.estoma.authentication;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SystemController {
  @GetMapping("/health")
  Map<String, String> health() { return Map.of("status", "ok", "service", "authentication-service"); }

  @GetMapping("/version")
  Map<String, String> version() { return Map.of("service", "authentication-service", "version", "0.1.0"); }
}
