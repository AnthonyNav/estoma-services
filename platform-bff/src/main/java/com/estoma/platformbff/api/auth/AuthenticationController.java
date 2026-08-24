package com.estoma.platformbff.api.auth;

import com.estoma.platformbff.application.security.AuthenticationEdgeService;
import com.estoma.platformbff.application.security.AuthenticationEdgeService.ClientMetadata;
import com.estoma.platformbff.contracts.http.auth.LoginRequest;
import com.estoma.platformbff.contracts.http.auth.LoginResponse;
import com.estoma.platformbff.contracts.http.auth.LogoutResponse;
import com.estoma.platformbff.contracts.http.auth.PasswordRecoveryRequest;
import com.estoma.platformbff.contracts.http.auth.PasswordResetRequest;
import com.estoma.platformbff.contracts.http.auth.RecoveryCodeRequest;
import com.estoma.platformbff.contracts.http.auth.RefreshRequest;
import com.estoma.platformbff.contracts.http.auth.RefreshResponse;
import com.estoma.platformbff.contracts.messaging.CommandSubmissionResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
public class AuthenticationController {
  private final AuthenticationEdgeService authentication;

  public AuthenticationController(AuthenticationEdgeService authentication) {
    this.authentication = authentication;
  }

  @PostMapping("/login")
  public LoginResponse login(
      @Valid @RequestBody LoginRequest request,
      HttpServletRequest httpRequest) {
    return authentication.login(
        request,
        new ClientMetadata(httpRequest.getRemoteAddr(), httpRequest.getHeader("User-Agent")));
  }

  @PostMapping("/refresh")
  public RefreshResponse refresh(@Valid @RequestBody RefreshRequest request) {
    return authentication.refresh(request);
  }

  @PostMapping("/logout")
  public LogoutResponse logout() {
    return authentication.logout();
  }

  @PostMapping("/password-recovery")
  public ResponseEntity<CommandSubmissionResult> requestRecovery(
      @Valid @RequestBody PasswordRecoveryRequest request,
      @RequestHeader("Idempotency-Key") String idempotencyKey) {
    return ResponseEntity.accepted()
        .body(authentication.requestPasswordRecovery(request, idempotencyKey));
  }

  @PostMapping("/password-recovery/validate")
  public RecoveryValidationResponse validateRecovery(
      @Valid @RequestBody RecoveryCodeRequest request) {
    return new RecoveryValidationResponse(authentication.validateRecoveryCode(request));
  }

  @PostMapping("/password-recovery/reset")
  public PasswordResetResponse resetPassword(
      @Valid @RequestBody PasswordResetRequest request) {
    return new PasswordResetResponse(authentication.resetPassword(request));
  }

  public record RecoveryValidationResponse(String result) {}
  public record PasswordResetResponse(boolean reset) {}
}
