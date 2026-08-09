package com.estoma.authentication.application.port;

import com.estoma.authentication.domain.account.AccountStatusChange;

/** Writes the audit trail for account lifecycle transitions. */
public interface AccountStatusHistoryRepository {

  void insert(AccountStatusChange change);
}
