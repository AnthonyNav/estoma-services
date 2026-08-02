package com.estoma.washexecution;

import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class SystemController {
  @GetMapping("/health")
  Map<String, String> health() { return Map.of("status", "ok", "service", "wash-execution-service"); }
  @GetMapping("/version")
  Map<String, String> version() { return Map.of("service", "wash-execution-service", "version", "0.1.0"); }
}
