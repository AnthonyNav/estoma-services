package com.estoma.users.application.port;

import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonStatus;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for the {@link Person} aggregate. Deliberately not a
 * generic CRUD repository: every write method mirrors one of the four
 * guarded SQL statements described in docs/system-contract.md §7, §13.
 *
 * <p>{@code insert} and the two update methods throw {@link
 * com.estoma.users.domain.PersonRejectedException} directly when they
 * detect a lost race against a concurrent writer (a unique-constraint
 * violation on {@code person_id} or on the normalized email) — this keeps
 * command handlers able to treat "rejected by domain validation" and
 * "rejected because another replica got there first" identically.
 */
public interface PersonRepository {

  Optional<Person> findById(UUID personId);

  Optional<Person> findByNormalizedEmail(String normalizedEmail);

  /**
   * Inserts a brand-new Person row.
   *
   * @throws com.estoma.users.domain.PersonRejectedException with {@code
   *     PERSON_ALREADY_EXISTS} if {@code person.personId()} is already
   *     taken, or {@code DUPLICATE_PERSON} if the normalized email is
   *     already claimed by a different person — both are lost-race
   *     translations of the corresponding unique constraints.
   */
  void insert(Person person);

  /**
   * Applies an {@code UpdatePerson} change. The row is only updated when
   * {@code person_id} matches and the current stored version equals
   * {@code expectedVersion}; {@code updated.version()} is what the row
   * moves to.
   *
   * @return rows affected: 1 on success, 0 if the person does not exist or
   *     {@code expectedVersion} no longer matches (the caller must re-read
   *     to disambiguate — see docs/system-contract.md §3 precedence).
   * @throws com.estoma.users.domain.PersonRejectedException with {@code
   *     DUPLICATE_PERSON} if the new normalized email is already claimed
   *     by a different person.
   */
  int updateProfile(Person updated, int expectedVersion);

  /**
   * Applies an {@code ActivatePerson}/{@code DeactivatePerson} change. The
   * row is only updated when {@code person_id}, {@code expectedVersion},
   * and {@code expectedCurrentStatus} all still hold.
   *
   * @return rows affected: 1 on success, 0 if the person does not exist,
   *     the version no longer matches, or the status has already moved
   *     away from {@code expectedCurrentStatus}.
   */
  int updateStatus(Person updated, int expectedVersion, PersonStatus expectedCurrentStatus);
}
