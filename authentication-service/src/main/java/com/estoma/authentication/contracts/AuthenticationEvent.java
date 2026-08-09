package com.estoma.authentication.contracts;

public enum AuthenticationEvent {
  ACCOUNT_CREATED("AccountCreated"),
  ACCOUNT_ACTIVATED("AccountActivated"),
  ACCOUNT_DEACTIVATED("AccountDeactivated"),
  LOGIN_IDENTIFIER_CHANGED("LoginIdentifierChanged"),
  STUDENT_LOGIN_IDENTIFIER_SYNCHRONIZATION_FAILED("StudentLoginIdentifierSynchronizationFailed"),
  PASSWORD_CHANGED("PasswordChanged"),
  TEMPORARY_PASSWORD_SET("TemporaryPasswordSet"),
  TEMPORARY_PASSWORD_REGENERATED("TemporaryPasswordRegenerated"),
  SESSION_REVOKED("SessionRevoked"),
  ALL_ACCOUNT_SESSIONS_REVOKED("AllAccountSessionsRevoked"),
  OTHER_ACCOUNT_SESSIONS_REVOKED("OtherAccountSessionsRevoked"),
  PASSWORD_RECOVERY_REQUESTED("PasswordRecoveryRequested");

  private final String wireName;

  AuthenticationEvent(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
