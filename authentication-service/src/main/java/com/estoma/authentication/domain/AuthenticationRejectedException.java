package com.estoma.authentication.domain;

/**
 * A functional rejection against any of this service's four aggregates
 * (Account, Credential, Session, PasswordRecoveryRequest) -- one shared
 * exception type, since they all draw from the same {@link RejectionCode}
 * catalog (commands.md §3).
 *
 * <p>For durable commands, this is caught INSIDE the dispatcher's
 * transaction and turned into a durable {@code Rejected} result. For
 * ephemeral RPC, it is caught the same way so that any state change already
 * applied before the rejection (e.g. incrementing {@code failed_attempts})
 * still commits, then a rejection response is returned directly -- RPC
 * never persists a durable rejection result (system_contract.md "RPC
 * Security Rules"). In neither case may it be allowed to unwind the
 * dispatcher's transaction.
 */
public final class AuthenticationRejectedException extends RuntimeException {

  private final RejectionCode code;

  public AuthenticationRejectedException(RejectionCode code, String message) {
    super(message);
    this.code = code;
  }

  public RejectionCode code() {
    return code;
  }
}
