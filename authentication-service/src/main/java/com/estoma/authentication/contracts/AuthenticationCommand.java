package com.estoma.authentication.contracts;

public enum AuthenticationCommand {
  CREATE_ACCOUNT("CreateAccount"),
  ACTIVATE_ACCOUNT("ActivateAccount"),
  DEACTIVATE_ACCOUNT("DeactivateAccount"),
  CHANGE_LOGIN_IDENTIFIER("ChangeLoginIdentifier"),
  REVOKE_ALL_ACCOUNT_SESSIONS("RevokeAllAccountSessions"),
  REQUEST_PASSWORD_RECOVERY("RequestPasswordRecovery");

  private final String wireName;

  AuthenticationCommand(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
