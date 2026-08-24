package com.estoma.platformbff.api.auth;

import com.estoma.platformbff.application.security.AuthenticationEdgeService;
import com.estoma.platformbff.contracts.http.auth.ChangePasswordRequest;
import com.estoma.platformbff.contracts.http.auth.LoginResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/me")
public class MePasswordController {
  private final AuthenticationEdgeService authentication;

  public MePasswordController(AuthenticationEdgeService authentication) {
    this.authentication = authentication;
  }

  @PostMapping("/password")
  public LoginResponse changePassword(@Valid @RequestBody ChangePasswordRequest request) {
    return authentication.changePassword(request);
  }
}
