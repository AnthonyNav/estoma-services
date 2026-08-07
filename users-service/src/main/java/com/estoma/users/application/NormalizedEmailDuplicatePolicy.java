package com.estoma.users.application;

import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.domain.Person;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * The only {@link DuplicatePersonPolicy} implementation shipped in this
 * PR. A future fuzzier rule (name + birthdate matching, say) would be a
 * new implementation of this same interface, selected in place of this
 * one — see docs/system-contract.md §6 for the revisit trigger (the mass
 * import matching strategy).
 */
@Component
class NormalizedEmailDuplicatePolicy implements DuplicatePersonPolicy {

  private final PersonRepository personRepository;

  NormalizedEmailDuplicatePolicy(PersonRepository personRepository) {
    this.personRepository = personRepository;
  }

  @Override
  public DuplicateVerdict check(String normalizedEmail, UUID excludingPersonId) {
    Optional<Person> match = personRepository.findByNormalizedEmail(normalizedEmail);
    if (match.isEmpty()) {
      return new DuplicateVerdict.None();
    }
    Person matched = match.get();
    if (matched.personId().equals(excludingPersonId)) {
      return new DuplicateVerdict.None();
    }
    return new DuplicateVerdict.Duplicate(matched.personId(), "institutional_email_normalized");
  }
}
