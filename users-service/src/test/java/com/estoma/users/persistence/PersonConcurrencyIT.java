package com.estoma.users.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies the optimistic-concurrency guard from
 * docs/system-contract.md §7: when two callers race with the same
 * {@code expectedVersion}, the database — not application-level locking —
 * ensures exactly one of them wins.
 */
class PersonConcurrencyIT extends AbstractPostgresIT {

  @Autowired private PersonRepository repository;

  @Test
  void exactlyOneOfTwoConcurrentUpdatesWithTheSameExpectedVersionSucceeds() throws Exception {
    Person person = newPerson();
    repository.insert(person);

    Callable<Integer> attemptA =
        () ->
            repository.updateProfile(
                person.withUpdatedProfile(
                    "Winner-A",
                    person.paternalSurname(),
                    person.maternalSurname(),
                    person.email(),
                    person.phone(),
                    Instant.now()),
                person.version());
    Callable<Integer> attemptB =
        () ->
            repository.updateProfile(
                person.withUpdatedProfile(
                    "Winner-B",
                    person.paternalSurname(),
                    person.maternalSurname(),
                    person.email(),
                    person.phone(),
                    Instant.now()),
                person.version());

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Integer>> futures = executor.invokeAll(List.of(attemptA, attemptB));
      int totalRowsAffected = 0;
      for (Future<Integer> future : futures) {
        totalRowsAffected += future.get();
      }

      assertThat(totalRowsAffected)
          .as("exactly one of the two racing updates should affect a row")
          .isEqualTo(1);
      assertThat(repository.findById(person.personId())).map(Person::version).contains(2);
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static Person newPerson() {
    UUID personId = UUID.randomUUID();
    return Person.createNew(
        personId,
        "Ada",
        "Lovelace",
        null,
        InstitutionalEmail.of("ada." + personId + "@example.edu"),
        null,
        Instant.now());
  }
}
