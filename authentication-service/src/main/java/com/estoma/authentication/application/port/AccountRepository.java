package com.estoma.authentication.application.port;

import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.account.Account;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountRepository {

  Optional<Account> findById(UUID accountId);

  Optional<Account> findByIdForUpdate(UUID accountId);

  Optional<Account> findByLoginIdentifier(LoginType loginType, String normalizedIdentifier);

  Optional<Account> findByLoginIdentifierForUpdate(LoginType loginType, String normalizedIdentifier);

  List<UUID> findIdsByPersonId(UUID personId);

  /** Stable keyset page ordered by accountId. */
  List<Account> listAfter(UUID cursorExclusive, int limit);

  void insert(Account account);

  int update(Account updated, int expectedVersion);
}
