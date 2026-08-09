package com.estoma.authentication.contracts.rpc;

/** Public authentication state carried by login and password-change responses. */
public enum AuthState {
  NORMAL,
  PASSWORD_CHANGE_REQUIRED
}
