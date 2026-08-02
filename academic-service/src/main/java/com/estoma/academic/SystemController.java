package com.estoma.academic;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SystemController {
  @GetMapping("/health")
  Map<String, String> health() { return Map.of("status", "ok", "service", "academic-service"); }
  @GetMapping("/version")
  Map<String, String> version() { return Map.of("service", "academic-service", "version", "0.1.0"); }
}
