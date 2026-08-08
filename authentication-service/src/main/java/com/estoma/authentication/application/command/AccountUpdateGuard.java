package com.estoma.authentication.application.command;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import java.util.UUID;

/**
 * Shared disambiguation for {@code AccountRepository#update}'s
 * zero-rows-affected outcome: the caller must re-read to tell {@code
 * ACCOUNT_NOT_FOUND} apart from {@code ACCOUNT_VERSION_CONFLICT} (mirrors
 * {@code users-service}'s {@code PersonRepository} contract for the same
 * ambiguity).
 */
final class AccountUpdateGuard {

  private AccountUpdateGuard() {}

  /** Throws the correct rejection for a zero-rows-affected {@code update}; never returns normally. */
  static void rejectForFailedUpdate(AccountRepository repository, UUID accountId, int expectedVersion) {
    Account current =
        repository.findById(accountId).orElseThrow(() -> new AuthenticationRejectedException(RejectionCode.ACCOUNT_NOT_FOUND, "account " + accountId + " does not exist"));
    if (current.version() != expectedVersion) {
      throw new AuthenticationRejectedException(RejectionCode.ACCOUNT_VERSION_CONFLICT, "expectedVersion no longer matches the current version");
    }
    // Version matches but the guarded predicate still failed: a concurrent transition already
    // moved the account to a state this caller's precondition no longer allows.
    throw new AuthenticationRejectedException(RejectionCode.ACCOUNT_VERSION_CONFLICT, "account state changed concurrently despite a matching version");
  }
}
