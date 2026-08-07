package com.estoma.users.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.users.application.port.PersonRepository;
import com.estoma.users.domain.InstitutionalEmail;
import com.estoma.users.domain.Person;
import com.estoma.users.domain.PersonRejectedException;
import com.estoma.users.domain.PersonStatus;
import com.estoma.users.domain.RejectionCode;
import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcPersonRepositoryIT extends AbstractPostgresIT {

  @Autowired private PersonRepository repository;

  @Test
  void insertThenFindByIdRoundTripsAllFields() {
    Person person = newPerson();

    repository.insert(person);
    Optional<Person> found = repository.findById(person.personId());

    assertThat(found).isPresent();
    assertThat(found.get().personId()).isEqualTo(person.personId());
    assertThat(found.get().firstName()).isEqualTo(person.firstName());
    assertThat(found.get().paternalSurname()).isEqualTo(person.paternalSurname());
    assertThat(found.get().email().value()).isEqualTo(person.email().value());
    assertThat(found.get().email().normalized()).isEqualTo(person.email().normalized());
    assertThat(found.get().status()).isEqualTo(PersonStatus.ACTIVE);
    assertThat(found.get().version()).isEqualTo(1);
  }

  @Test
  void findByIdReturnsEmptyForUnknownPerson() {
    assertThat(repository.findById(UUID.randomUUID())).isEmpty();
  }

  @Test
  void findByNormalizedEmailFindsInsertedPerson() {
    Person person = newPerson();
    repository.insert(person);

    Optional<Person> found = repository.findByNormalizedEmail(person.email().normalized());

    assertThat(found).map(Person::personId).contains(person.personId());
  }

  @Test
  void insertRejectsDuplicatePersonId() {
    UUID personId = UUID.randomUUID();
    repository.insert(newPersonWithId(personId));

    assertThatThrownBy(() -> repository.insert(newPersonWithId(personId)))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e ->
                assertThat(((PersonRejectedException) e).code())
                    .isEqualTo(RejectionCode.PERSON_ALREADY_EXISTS));
  }

  @Test
  void insertRejectsDuplicateNormalizedEmailAcrossDifferentPersons() {
    String sharedEmail = "shared." + UUID.randomUUID() + "@example.edu";
    repository.insert(
        Person.createNew(
            UUID.randomUUID(),
            "Ada",
            "Lovelace",
            null,
            InstitutionalEmail.of(sharedEmail),
            null,
            Instant.now()));

    assertThatThrownBy(
            () ->
                repository.insert(
                    Person.createNew(
                        UUID.randomUUID(),
                        "Grace",
                        "Hopper",
                        null,
                        InstitutionalEmail.of(sharedEmail),
                        null,
                        Instant.now())))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e ->
                assertThat(((PersonRejectedException) e).code())
                    .isEqualTo(RejectionCode.DUPLICATE_PERSON));
  }

  @Test
  void updateProfileReturnsOneRowAffectedWhenVersionMatches() {
    Person person = newPerson();
    repository.insert(person);

    Person updated =
        person.withUpdatedProfile(
            "Augusta",
            person.paternalSurname(),
            person.maternalSurname(),
            person.email(),
            person.phone(),
            Instant.now());

    int rowsAffected = repository.updateProfile(updated, person.version());

    assertThat(rowsAffected).isEqualTo(1);
    assertThat(repository.findById(person.personId())).map(Person::firstName).contains("Augusta");
    assertThat(repository.findById(person.personId())).map(Person::version).contains(2);
  }

  @Test
  void updateProfileReturnsZeroRowsAffectedWhenExpectedVersionIsStale() {
    Person person = newPerson();
    repository.insert(person);

    Person updated =
        person.withUpdatedProfile(
            "Augusta",
            person.paternalSurname(),
            person.maternalSurname(),
            person.email(),
            person.phone(),
            Instant.now());

    int rowsAffected = repository.updateProfile(updated, person.version() + 99);

    assertThat(rowsAffected).isZero();
    // Nothing changed.
    assertThat(repository.findById(person.personId())).map(Person::firstName).contains("Ada");
  }

  @Test
  void updateProfileRejectsWhenNewEmailCollidesWithAnotherPerson() {
    String otherEmail = "other." + UUID.randomUUID() + "@example.edu";
    repository.insert(
        Person.createNew(
            UUID.randomUUID(), "Grace", "Hopper", null, InstitutionalEmail.of(otherEmail), null, Instant.now()));

    Person person = newPerson();
    repository.insert(person);

    Person updated =
        person.withUpdatedProfile(
            person.firstName(),
            person.paternalSurname(),
            person.maternalSurname(),
            InstitutionalEmail.of(otherEmail),
            person.phone(),
            Instant.now());

    assertThatThrownBy(() -> repository.updateProfile(updated, person.version()))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e ->
                assertThat(((PersonRejectedException) e).code())
                    .isEqualTo(RejectionCode.DUPLICATE_PERSON));
  }

  @Test
  void updateProfileAllowsReSubmittingTheSameEmailForTheSamePerson() {
    Person person = newPerson();
    repository.insert(person);

    Person updated =
        person.withUpdatedProfile(
            "Augusta",
            person.paternalSurname(),
            person.maternalSurname(),
            person.email(), // unchanged, same normalized value as its own row
            person.phone(),
            Instant.now());

    int rowsAffected = repository.updateProfile(updated, person.version());

    assertThat(rowsAffected).isEqualTo(1);
  }

  @Test
  void updateStatusAppliesGuardedTransitionAndReturnsRowsAffected() {
    Person person = newPerson();
    repository.insert(person);

    Person deactivated = person.deactivated(Instant.now());

    int rowsAffected =
        repository.updateStatus(deactivated, person.version(), PersonStatus.ACTIVE);

    assertThat(rowsAffected).isEqualTo(1);
    Optional<Person> found = repository.findById(person.personId());
    assertThat(found).map(Person::status).contains(PersonStatus.INACTIVE);
    assertThat(found).map(Person::deactivatedAt).map(java.util.Objects::nonNull).contains(true);
  }

  @Test
  void updateStatusReturnsZeroWhenExpectedCurrentStatusNoLongerMatches() {
    Person person = newPerson();
    repository.insert(person);
    Person deactivated = person.deactivated(Instant.now());
    repository.updateStatus(deactivated, person.version(), PersonStatus.ACTIVE);

    // Now the row is INACTIVE; attempting another deactivate-from-ACTIVE
    // guard should affect zero rows (status guard fails), simulating a
    // second concurrent DeactivatePerson command losing the race.
    Person secondAttempt = deactivated.activated(Instant.now()).deactivated(Instant.now());
    int rowsAffected =
        repository.updateStatus(secondAttempt, person.version(), PersonStatus.ACTIVE);

    assertThat(rowsAffected).isZero();
  }

  private static Person newPerson() {
    return newPersonWithId(UUID.randomUUID());
  }

  private static Person newPersonWithId(UUID personId) {
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
