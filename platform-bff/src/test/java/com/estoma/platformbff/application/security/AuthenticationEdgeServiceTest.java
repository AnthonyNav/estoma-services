package com.estoma.platformbff.application.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.estoma.platformbff.application.commands.CommandGatewayService;
import com.estoma.platformbff.application.port.MessageSerializer;
import com.estoma.platformbff.contracts.http.auth.LoginRequest;
import com.estoma.platformbff.contracts.http.auth.RefreshRequest;
import com.estoma.platformbff.contracts.messaging.ActorContext;
import com.estoma.platformbff.infrastructure.messaging.MessageEnvelopeCodec;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AuthenticationEdgeServiceTest {
  private SecurityRpcPort rpc;
  private CommandGatewayService commands;
  private CurrentSecurityPrincipal principal;
  private AuthenticationEdgeService service;

  @BeforeEach
  void setUp() {
    rpc = mock(SecurityRpcPort.class);
    commands = mock(CommandGatewayService.class);
    principal = mock(CurrentSecurityPrincipal.class);
    MessageSerializer serializer = new MessageEnvelopeCodec(
        JsonMapper.builder().findAndAddModules().build());
    RefreshSingleFlight singleFlight = new RefreshSingleFlight() {
      @Override
      public <T> T execute(UUID sessionId, Supplier<T> operation) {
        return operation.get();
      }
    };
    service = new AuthenticationEdgeService(rpc, singleFlight, commands, principal, serializer);
  }

  @Test
  void passwordChangeRequiredLoginAcceptsMissingRefreshToken() {
    UUID accountId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(rpc.call(eq("AuthenticateAccount"), eq(ActorContext.anonymous()), isNull(), isNull(), anyString()))
        .thenReturn(new SecurityRpcPort.RpcCallResult(
            "SUCCEEDED",
            "{\"accountId\":\"" + accountId + "\",\"sessionId\":\"" + sessionId
                + "\",\"accessToken\":\"access\",\"refreshToken\":null,"
                + "\"authState\":\"PASSWORD_CHANGE_REQUIRED\"}",
            null));

    var response = service.login(
        new LoginRequest("USERNAME", "ada", "temporary-secret", "browser"),
        new AuthenticationEdgeService.ClientMetadata("127.0.0.1", "UA"));

    assertThat(response.authState()).isEqualTo("PASSWORD_CHANGE_REQUIRED");
    assertThat(response.refreshToken()).isNull();
  }

  @Test
  void refreshUsesSessionSecretAndNeverConsultsJwtPrincipal() {
    UUID sessionId = UUID.randomUUID();
    Instant expiresAt = Instant.parse("2026-09-01T00:00:00Z");
    when(rpc.call(eq("RefreshSession"), eq(ActorContext.anonymous()), isNull(), isNull(), anyString()))
        .thenReturn(new SecurityRpcPort.RpcCallResult(
            "SUCCEEDED",
            "{\"accessToken\":\"new-access\",\"refreshToken\":\"new-refresh\","
                + "\"expiresAt\":\"" + expiresAt + "\"}",
            null));

    var response = service.refresh(new RefreshRequest(sessionId, "old-refresh"));

    assertThat(response.accessToken()).isEqualTo("new-access");
    assertThat(response.refreshToken()).isEqualTo("new-refresh");
    verify(rpc).call(
        eq("RefreshSession"), eq(ActorContext.anonymous()), isNull(), isNull(), anyString());
    verifyNoInteractions(principal);
  }

  @Test
  void logoutDerivesAccountAndSessionFromSignedPrincipal() {
    UUID accountId = UUID.randomUUID();
    UUID personId = UUID.randomUUID();
    UUID sessionId = UUID.randomUUID();
    when(principal.current()).thenReturn(Optional.of(
        new CurrentSecurityPrincipal.Principal(accountId, personId, sessionId, "NORMAL")));
    when(rpc.call(
        eq("RevokeSession"), eq(ActorContext.account(accountId, "SELF", null)),
        eq(accountId), isNull(), anyString()))
        .thenReturn(new SecurityRpcPort.RpcCallResult("SUCCEEDED", "{\"revoked\":true}", null));

    assertThat(service.logout().revoked()).isTrue();

    verify(rpc).call(
        eq("RevokeSession"), eq(ActorContext.account(accountId, "SELF", null)),
        eq(accountId), isNull(), anyString());
  }
}
