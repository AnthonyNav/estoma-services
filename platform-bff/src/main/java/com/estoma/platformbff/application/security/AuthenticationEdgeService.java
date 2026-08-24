package com.estoma.platformbff.application.security;

import com.estoma.platformbff.application.commands.CommandGatewayService;
import com.estoma.platformbff.application.port.MessageSerializer;
import com.estoma.platformbff.contracts.http.auth.ChangePasswordRequest;
import com.estoma.platformbff.contracts.http.auth.LoginRequest;
import com.estoma.platformbff.contracts.http.auth.LoginResponse;
import com.estoma.platformbff.contracts.http.auth.LogoutResponse;
import com.estoma.platformbff.contracts.http.auth.PasswordRecoveryRequest;
import com.estoma.platformbff.contracts.http.auth.PasswordResetRequest;
import com.estoma.platformbff.contracts.http.auth.RecoveryCodeRequest;
import com.estoma.platformbff.contracts.http.auth.RefreshRequest;
import com.estoma.platformbff.contracts.http.auth.RefreshResponse;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.contracts.messaging.CommandSubmission;
import com.estoma.platformbff.contracts.messaging.CommandSubmissionResult;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AuthenticationEdgeService {
  private static final Set<String> LOGIN_TYPES = Set.of("MATRICULA", "USERNAME");
  private static final Set<String> AUTH_STATES = Set.of("NORMAL", "PASSWORD_CHANGE_REQUIRED");

  private final SecurityRpcPort rpc;
  private final RefreshSingleFlight refreshSingleFlight;
  private final CommandGatewayService commands;
  private final CurrentSecurityPrincipal principal;
  private final MessageSerializer serializer;

  public AuthenticationEdgeService(
      SecurityRpcPort rpc,
      RefreshSingleFlight refreshSingleFlight,
      CommandGatewayService commands,
      CurrentSecurityPrincipal principal,
      MessageSerializer serializer) {
    this.rpc = rpc;
    this.refreshSingleFlight = refreshSingleFlight;
    this.commands = commands;
    this.principal = principal;
    this.serializer = serializer;
  }

  public LoginResponse login(LoginRequest request, ClientMetadata client) {
    var payload = new LoginRpcRequest(
        loginType(request.loginType()),
        request.loginIdentifier(),
        request.password(),
        trimTo(request.deviceName(), 200),
        trimTo(client.ipAddress(), 128),
        trimTo(client.userAgent(), 512));
    LoginRpcResponse response = requireSuccess(
        "AuthenticateAccount",
        rpc.call("AuthenticateAccount", ActorContext.anonymous(), null, null, serializer.write(payload)),
        LoginRpcResponse.class);
    validateLoginResponse(response);
    return new LoginResponse(
        response.accountId(), response.sessionId(), response.accessToken(),
        response.refreshToken(), response.authState());
  }

  public RefreshResponse refresh(RefreshRequest request) {
    return refreshSingleFlight.execute(request.sessionId(), () -> {
      RefreshRpcResponse response = requireSuccess(
          "RefreshSession",
          rpc.call("RefreshSession", ActorContext.anonymous(), null, null, serializer.write(request)),
          RefreshRpcResponse.class);
      if (blank(response.accessToken()) || blank(response.refreshToken()) || response.expiresAt() == null) {
        throw new SecurityRpcUnavailableException("RefreshSession returned an incomplete response");
      }
      return new RefreshResponse(response.accessToken(), response.refreshToken(), response.expiresAt());
    });
  }

  public LogoutResponse logout() {
    var current = requirePrincipal();
    RevokeRpcResponse response = requireSuccess(
        "RevokeSession",
        rpc.call(
            "RevokeSession",
            ActorContext.account(current.accountId(), "SELF", null),
            current.accountId(),
            null,
            serializer.write(new SessionRequest(current.sessionId()))),
        RevokeRpcResponse.class);
    return new LogoutResponse(response.revoked());
  }

  public CommandSubmissionResult requestPasswordRecovery(
      PasswordRecoveryRequest request, String idempotencyKey) {
    var payload = new RecoveryCommand(loginType(request.loginType()), request.loginIdentifier());
    return commands.submit(new CommandSubmission(
        "password-recovery",
        "RequestPasswordRecovery",
        serializer.write(payload),
        idempotencyKey,
        null,
        ActorContext.anonymous(),
        null,
        null));
  }

  public String validateRecoveryCode(RecoveryCodeRequest request) {
    RecoveryValidationResponse response = requireSuccess(
        "ValidateRecoveryCode",
        rpc.call(
            "ValidateRecoveryCode",
            ActorContext.anonymous(),
            null,
            null,
            serializer.write(new RecoveryValidation(
                loginType(request.loginType()), request.loginIdentifier(), request.code()))),
        RecoveryValidationResponse.class);
    if (!Set.of("VALID", "INVALID", "EXPIRED").contains(response.result())) {
      throw new SecurityRpcUnavailableException("ValidateRecoveryCode returned an invalid result");
    }
    return response.result();
  }

  public boolean resetPassword(PasswordResetRequest request) {
    PasswordResetResponse response = requireSuccess(
        "ResetPassword",
        rpc.call(
            "ResetPassword",
            ActorContext.anonymous(),
            null,
            null,
            serializer.write(new PasswordReset(
                loginType(request.loginType()), request.loginIdentifier(),
                request.code(), request.newPassword()))),
        PasswordResetResponse.class);
    return response.reset();
  }

  public LoginResponse changePassword(ChangePasswordRequest request) {
    var current = requirePrincipal();
    ChangePasswordRpcResponse response = requireSuccess(
        "ChangePassword",
        rpc.call(
            "ChangePassword",
            ActorContext.account(current.accountId(), "SELF", null),
            current.accountId(),
            null,
            serializer.write(new ChangePasswordRpcRequest(
                current.sessionId(), request.currentPassword(), request.newPassword()))),
        ChangePasswordRpcResponse.class);
    if (!current.sessionId().equals(response.sessionId())
        || blank(response.accessToken())
        || blank(response.refreshToken())
        || !"NORMAL".equals(response.authState())) {
      throw new SecurityRpcUnavailableException("ChangePassword returned an invalid response");
    }
    return new LoginResponse(
        current.accountId(), response.sessionId(), response.accessToken(),
        response.refreshToken(), response.authState());
  }

  private CurrentSecurityPrincipal.Principal requirePrincipal() {
    return principal.current().orElseThrow(AuthenticationRequiredException::new);
  }

  private <T> T requireSuccess(
      String operation, SecurityRpcPort.RpcCallResult result, Class<T> responseType) {
    if (result == null) {
      throw new SecurityRpcUnavailableException(operation + " returned an empty response");
    }
    if ("REJECTED".equals(result.status())) {
      throw new AuthenticationRejectedException();
    }
    if (!"SUCCEEDED".equals(result.status()) || blank(result.dataJson())) {
      throw new SecurityRpcUnavailableException(operation + " failed remotely");
    }
    try {
      return serializer.read(result.dataJson(), responseType);
    } catch (RuntimeException malformed) {
      throw new SecurityRpcUnavailableException(operation + " returned malformed data", malformed);
    }
  }

  private static void validateLoginResponse(LoginRpcResponse response) {
    if (response == null || response.accountId() == null || response.sessionId() == null
        || blank(response.accessToken()) || !AUTH_STATES.contains(response.authState())) {
      throw new SecurityRpcUnavailableException("AuthenticateAccount returned an incomplete response");
    }
    if ("NORMAL".equals(response.authState()) && blank(response.refreshToken())) {
      throw new SecurityRpcUnavailableException("NORMAL session omitted refreshToken");
    }
    if ("PASSWORD_CHANGE_REQUIRED".equals(response.authState()) && !blank(response.refreshToken())) {
      throw new SecurityRpcUnavailableException("restricted session unexpectedly returned refreshToken");
    }
  }

  private static String loginType(String value) {
    if (value == null) throw new IllegalArgumentException("loginType is required");
    String normalized = value.toUpperCase(Locale.ROOT);
    if (!LOGIN_TYPES.contains(normalized)) throw new IllegalArgumentException("loginType is invalid");
    return normalized;
  }

  private static String trimTo(String value, int max) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.length() <= max ? trimmed : trimmed.substring(0, max);
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  public record ClientMetadata(String ipAddress, String userAgent) {}

  private record LoginRpcRequest(
      String loginType, String loginIdentifier, String password,
      String deviceName, String ipAddress, String userAgent) {}
  private record LoginRpcResponse(
      UUID accountId, UUID sessionId, String accessToken, String refreshToken, String authState) {}
  private record RefreshRpcResponse(String accessToken, String refreshToken, Instant expiresAt) {}
  private record SessionRequest(UUID sessionId) {}
  private record RevokeRpcResponse(boolean revoked) {}
  private record RecoveryCommand(String loginType, String loginIdentifier) {}
  private record RecoveryValidation(String loginType, String loginIdentifier, String code) {}
  private record RecoveryValidationResponse(String result) {}
  private record PasswordReset(String loginType, String loginIdentifier, String code, String newPassword) {}
  private record PasswordResetResponse(boolean reset) {}
  private record ChangePasswordRpcRequest(UUID sessionId, String currentPassword, String newPassword) {}
  private record ChangePasswordRpcResponse(
      UUID sessionId, String accessToken, String refreshToken, String authState) {}
}
