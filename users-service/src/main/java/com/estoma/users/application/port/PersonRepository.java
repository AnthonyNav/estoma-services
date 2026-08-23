package com.estoma.users.application.port;

import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PersonRepository {

  Optional<Person> findById(UUID personId);

  Optional<Person> findByNormalizedEmail(String normalizedEmail);

  /** Stable keyset page ordered by personId. Callers may request limit + 1 to derive hasMore. */
  List<Person> listAfter(UUID cursorExclusive, int limit);

  void insert(Person person);

  int updateProfile(Person updated, int expectedVersion);

  int updateStatus(Person updated, int expectedVersion, PersonStatus expectedCurrentStatus);
}
