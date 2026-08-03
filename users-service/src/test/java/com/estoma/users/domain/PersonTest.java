package com.estoma.users.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PersonTest {

  private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
  private static final Instant LATER = NOW.plus(1, ChronoUnit.HOURS);

  @Test
  void createNewStartsAtVersionOneAndActive() {
    Person person = newPerson();

    assertThat(person.version()).isEqualTo(1);
    assertThat(person.status()).isEqualTo(PersonStatus.ACTIVE);
    assertThat(person.deactivatedAt()).isNull();
    assertThat(person.createdAt()).isEqualTo(NOW);
    assertThat(person.updatedAt()).isEqualTo(NOW);
  }

  @Test
  void createNewRejectsBlankFirstName() {
    assertThatThrownBy(
            () ->
                Person.createNew(
                    UUID.randomUUID(), "  ", "Lovelace", null, email(), null, NOW))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.INVALID_PERSON_DATA));
  }

  @Test
  void createNewRejectsBlankPaternalSurname() {
    assertThatThrownBy(
            () -> Person.createNew(UUID.randomUUID(), "Ada", "", null, email(), null, NOW))
        .isInstanceOf(PersonRejectedException.class);
  }

  @Test
  void createNewAcceptsNullMaternalSurname() {
    Person person =
        Person.createNew(UUID.randomUUID(), "Ada", "Lovelace", null, email(), null, NOW);

    assertThat(person.maternalSurname()).isNull();
  }

  @Test
  void createNewRejectsBlankButPresentMaternalSurname() {
    assertThatThrownBy(
            () -> Person.createNew(UUID.randomUUID(), "Ada", "Lovelace", "   ", email(), null, NOW))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.INVALID_PERSON_DATA));
  }

  @Test
  void createNewTreatsBlankPhoneAsAbsent() {
    Person person = Person.createNew(UUID.randomUUID(), "Ada", "Lovelace", null, email(), "   ", NOW);

    assertThat(person.phone()).isNull();
  }

  @Test
  void withUpdatedProfileIncrementsVersionAndUpdatedAtOnly() {
    Person original = newPerson();

    Person updated =
        original.withUpdatedProfile(
            "Augusta", "Lovelace", "King", InstitutionalEmail.of("augusta@example.edu"), "555", LATER);

    assertThat(updated.version()).isEqualTo(original.version() + 1);
    assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    assertThat(updated.updatedAt()).isEqualTo(LATER);
    assertThat(updated.status()).isEqualTo(original.status());
    assertThat(updated.deactivatedAt()).isEqualTo(original.deactivatedAt());
    assertThat(updated.firstName()).isEqualTo("Augusta");
  }

  @Test
  void deactivatedSetsInactiveAndDeactivatedAtAndIncrementsVersion() {
    Person active = newPerson();

    Person inactive = active.deactivated(LATER);

    assertThat(inactive.status()).isEqualTo(PersonStatus.INACTIVE);
    assertThat(inactive.deactivatedAt()).isEqualTo(LATER);
    assertThat(inactive.version()).isEqualTo(active.version() + 1);
  }

  @Test
  void deactivatingAnAlreadyInactivePersonIsRejected() {
    Person inactive = newPerson().deactivated(LATER);

    assertThatThrownBy(() -> inactive.deactivated(LATER.plusSeconds(1)))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e ->
                assertThat(((PersonRejectedException) e).code())
                    .isEqualTo(RejectionCode.PERSON_ALREADY_INACTIVE));
  }

  @Test
  void activatedClearsDeactivatedAtAndIncrementsVersion() {
    Person inactive = newPerson().deactivated(LATER);

    Person active = inactive.activated(LATER.plusSeconds(1));

    assertThat(active.status()).isEqualTo(PersonStatus.ACTIVE);
    assertThat(active.deactivatedAt()).isNull();
    assertThat(active.version()).isEqualTo(inactive.version() + 1);
  }

  @Test
  void activatingAnAlreadyActivePersonIsRejected() {
    Person active = newPerson();

    assertThatThrownBy(() -> active.activated(LATER))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e ->
                assertThat(((PersonRejectedException) e).code())
                    .isEqualTo(RejectionCode.PERSON_ALREADY_ACTIVE));
  }

  @Test
  void reconstituteRejectsInactiveWithoutDeactivatedAt() {
    assertThatThrownBy(
            () ->
                Person.reconstitute(
                    UUID.randomUUID(),
                    "Ada",
                    "Lovelace",
                    null,
                    email(),
                    null,
                    PersonStatus.INACTIVE,
                    2,
                    NOW,
                    NOW,
                    null))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void reconstituteRejectsActiveWithDeactivatedAt() {
    assertThatThrownBy(
            () ->
                Person.reconstitute(
                    UUID.randomUUID(),
                    "Ada",
                    "Lovelace",
                    null,
                    email(),
                    null,
                    PersonStatus.ACTIVE,
                    2,
                    NOW,
                    NOW,
                    NOW))
        .isInstanceOf(IllegalStateException.class);
  }

  private static Person newPerson() {
    return Person.createNew(UUID.randomUUID(), "Ada", "Lovelace", null, email(), null, NOW);
  }

  private static InstitutionalEmail email() {
    return InstitutionalEmail.of("ada.lovelace@example.edu");
  }
}
