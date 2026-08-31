package com.estoma.platformbff.api.auth;

import com.estoma.platformbff.application.session.CurrentSessionContextService;
import com.estoma.platformbff.contracts.http.auth.CurrentSessionResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
  private final CurrentSessionContextService context;

  public MeController(CurrentSessionContextService context) {
    this.context = context;
  }

  @GetMapping
  public ResponseEntity<CurrentSessionResponse> current() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(context.current());
  }
}
