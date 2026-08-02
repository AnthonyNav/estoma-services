package com.estoma.appointments;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SystemController {
  @GetMapping("/health")
  Map<String, String> health() { return Map.of("status", "ok", "service", "appointments-service"); }
  @GetMapping("/version")
  Map<String, String> version() { return Map.of("service", "appointments-service", "version", "0.1.0"); }
}
