package com.estoma.users.application.port;

import com.estoma.users.domain.PersonStatusChange;

/** Persistence port for the append-only {@code person_status_history} log. */
public interface PersonStatusHistoryRepository {

  void insert(PersonStatusChange change);
}
