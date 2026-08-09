package com.estoma.authentication.contracts;

public enum AuthenticationRpcOperation {
  AUTHENTICATE_ACCOUNT("AuthenticateAccount"),
  REFRESH_SESSION("RefreshSession"),
  REVOKE_SESSION("RevokeSession"),
  VALIDATE_RECOVERY_CODE("ValidateRecoveryCode"),
  RESET_PASSWORD("ResetPassword"),
  CHANGE_PASSWORD("ChangePassword"),
  SET_TEMPORARY_PASSWORD("SetTemporaryPassword"),
  REGENERATE_TEMPORARY_PASSWORD("RegenerateTemporaryPassword"),
  GET_RECOVERY_CODE_FOR_DELIVERY("GetRecoveryCodeForDelivery");

  private final String wireName;

  AuthenticationRpcOperation(String wireName) {
    this.wireName = wireName;
  }

  public String wireName() {
    return wireName;
  }
}
