package com.estoma.authentication.application.port;

import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.account.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for {@link Account}. A single guarded {@link
 * #update(Account, int)} covers every transition (durable-command-driven or
 * RPC-driven): callers always read the current version first, transition
 * the aggregate, and pass the version they read as {@code expectedVersion}
 * -- RPC callers don't supply one themselves, but the handler still applies
 * the same optimistic-concurrency discipline internally for safety.
 */
public interface AccountRepository {

  Optional<Account> findById(UUID accountId);

  Optional<Account> findByLoginIdentifier(LoginType loginType, String normalizedIdentifier);

  /** Returns every account logically owned by the Person; no cross-service FK is required. */
  List<UUID> findIdsByPersonId(UUID personId);

  /**
   * @throws com.estoma.authentication.domain.AuthenticationRejectedException with {@code
   *     ACCOUNT_ALREADY_EXISTS} if {@code account.accountId()} is taken, or {@code
   *     LOGIN_IDENTIFIER_ALREADY_USED} if the normalized identifier is already claimed.
   */
  void insert(Account account);

  /**
   * @return rows affected: 1 on success, 0 if the account does not exist or {@code expectedVersion}
   *     no longer matches -- the caller must re-read to disambiguate {@code ACCOUNT_NOT_FOUND} from
   *     {@code ACCOUNT_VERSION_CONFLICT} (and, for activation, {@code ACCOUNT_ALREADY_ACTIVE}).
   * @throws com.estoma.authentication.domain.AuthenticationRejectedException with {@code
   *     LOGIN_IDENTIFIER_ALREADY_USED} if a changed identifier collides with another account.
   */
  int update(Account updated, int expectedVersion);
}
