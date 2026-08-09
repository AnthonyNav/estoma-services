package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticationContractTests {

  @Test
  void exposesCanonicalLoginTypesAndAccountStatuses() {
    assertThat(LoginType.values()).containsExactly(LoginType.MATRICULA, LoginType.USERNAME);
    assertThat(AccountStatus.values()).containsExactly(AccountStatus.ACTIVE, AccountStatus.INACTIVE);
  }

  @Test
  void exposesCanonicalDurableCommandNames() {
    assertThat(AuthenticationCommand.CREATE_ACCOUNT.wireName()).isEqualTo("CreateAccount");
    assertThat(AuthenticationCommand.ACTIVATE_ACCOUNT.wireName()).isEqualTo("ActivateAccount");
    assertThat(AuthenticationCommand.DEACTIVATE_ACCOUNT.wireName()).isEqualTo("DeactivateAccount");
    assertThat(AuthenticationCommand.CHANGE_LOGIN_IDENTIFIER.wireName()).isEqualTo("ChangeLoginIdentifier");
    assertThat(AuthenticationCommand.REVOKE_ALL_ACCOUNT_SESSIONS.wireName()).isEqualTo("RevokeAllAccountSessions");
    assertThat(AuthenticationCommand.REQUEST_PASSWORD_RECOVERY.wireName()).isEqualTo("RequestPasswordRecovery");
  }

  @Test
  void exposesCanonicalRpcOperationNames() {
    assertThat(AuthenticationRpcOperation.AUTHENTICATE_ACCOUNT.wireName()).isEqualTo("AuthenticateAccount");
    assertThat(AuthenticationRpcOperation.REFRESH_SESSION.wireName()).isEqualTo("RefreshSession");
    assertThat(AuthenticationRpcOperation.REVOKE_SESSION.wireName()).isEqualTo("RevokeSession");
    assertThat(AuthenticationRpcOperation.VALIDATE_RECOVERY_CODE.wireName()).isEqualTo("ValidateRecoveryCode");
    assertThat(AuthenticationRpcOperation.RESET_PASSWORD.wireName()).isEqualTo("ResetPassword");
    assertThat(AuthenticationRpcOperation.CHANGE_PASSWORD.wireName()).isEqualTo("ChangePassword");
    assertThat(AuthenticationRpcOperation.SET_TEMPORARY_PASSWORD.wireName()).isEqualTo("SetTemporaryPassword");
    assertThat(AuthenticationRpcOperation.REGENERATE_TEMPORARY_PASSWORD.wireName()).isEqualTo("RegenerateTemporaryPassword");
    assertThat(AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY.wireName()).isEqualTo("GetRecoveryCodeForDelivery");
  }

  @Test
  void exposesCanonicalEventNames() {
    assertThat(AuthenticationEvent.ACCOUNT_CREATED.wireName()).isEqualTo("AccountCreated");
    assertThat(AuthenticationEvent.ACCOUNT_ACTIVATED.wireName()).isEqualTo("AccountActivated");
    assertThat(AuthenticationEvent.ACCOUNT_DEACTIVATED.wireName()).isEqualTo("AccountDeactivated");
    assertThat(AuthenticationEvent.LOGIN_IDENTIFIER_CHANGED.wireName()).isEqualTo("LoginIdentifierChanged");
    assertThat(AuthenticationEvent.STUDENT_LOGIN_IDENTIFIER_SYNCHRONIZATION_FAILED.wireName())
        .isEqualTo("StudentLoginIdentifierSynchronizationFailed");
    assertThat(AuthenticationEvent.PASSWORD_CHANGED.wireName()).isEqualTo("PasswordChanged");
    assertThat(AuthenticationEvent.TEMPORARY_PASSWORD_SET.wireName()).isEqualTo("TemporaryPasswordSet");
    assertThat(AuthenticationEvent.TEMPORARY_PASSWORD_REGENERATED.wireName()).isEqualTo("TemporaryPasswordRegenerated");
    assertThat(AuthenticationEvent.SESSION_REVOKED.wireName()).isEqualTo("SessionRevoked");
    assertThat(AuthenticationEvent.ALL_ACCOUNT_SESSIONS_REVOKED.wireName()).isEqualTo("AllAccountSessionsRevoked");
    assertThat(AuthenticationEvent.PASSWORD_RECOVERY_REQUESTED.wireName()).isEqualTo("PasswordRecoveryRequested");
  }

  @Test
  void exposesCanonicalMessagingNames() {
    assertThat(MessagingNames.COMMANDS_EXCHANGE).isEqualTo("platform.commands");
    assertThat(MessagingNames.RESULTS_EXCHANGE).isEqualTo("platform.results");
    assertThat(MessagingNames.EVENTS_EXCHANGE).isEqualTo("platform.events");
    assertThat(MessagingNames.SECURITY_RPC_EXCHANGE).isEqualTo("platform.security.rpc");
    assertThat(MessagingNames.AUTHENTICATION_COMMAND_QUEUE).isEqualTo("commands.authentication");
    assertThat(MessagingNames.AUTHENTICATION_RPC_QUEUE).isEqualTo("rpc.authentication");
    assertThat(MessagingNames.BFF_RESULTS_QUEUE).isEqualTo("results.bff");
  }
}
