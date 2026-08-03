package com.estoma.users.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * The aggregate root of {@code users-service}: the shared personal
 * identity of the platform, independent of accounts, credentials,
 * sessions, roles, or academic information (data-model.md §1).
 *
 * <p>Immutable by design: every transition returns a new instance rather
 * than mutating in place. The actual row update is a guarded SQL statement
 * executed by {@code JdbcPersonRepository} (see docs/system-contract.md
 * §7, §13) — this class exists to validate field values against the same
 * invariants the database enforces (defense in depth, and a much better
 * {@link RejectionCode#INVALID_PERSON_DATA} than a raw constraint
 * violation surfacing as a technical failure) and to compute what the
 * post-transition state and derived event payload should look like.
 *
 * <p>{@code personId} is caller-generated and immutable
 * (data-model.md §8 rule 1, enforced again at the database by a trigger).
 */
public final class Person {

  private final UUID personId;
  private final String firstName;
  private final String paternalSurname;
  private final String maternalSurname;
  private final InstitutionalEmail email;
  private final String phone;
  private final PersonStatus status;
  private final int version;
  private final Instant createdAt;
  private final Instant updatedAt;
  private final Instant deactivatedAt;

  private Person(
      UUID personId,
      String firstName,
      String paternalSurname,
      String maternalSurname,
      InstitutionalEmail email,
      String phone,
      PersonStatus status,
      int version,
      Instant createdAt,
      Instant updatedAt,
      Instant deactivatedAt) {
    this.personId = requireNonNull(personId, "personId");
    this.firstName = requireNonBlank(firstName, "firstName");
    this.paternalSurname = requireNonBlank(paternalSurname, "paternalSurname");
    this.maternalSurname = requireNonBlankWhenPresent(maternalSurname, "maternalSurname");
    this.email = requireNonNull(email, "institutionalEmail");
    this.phone = normalizeBlankToNull(phone);
    this.status = requireNonNull(status, "status");
    if (version <= 0) {
      throw new PersonRejectedException(RejectionCode.INVALID_PERSON_DATA, "version must be positive");
    }
    this.version = version;
    this.createdAt = requireNonNull(createdAt, "createdAt");
    this.updatedAt = requireNonNull(updatedAt, "updatedAt");
    // Mirrors ck_persons_deactivation_state exactly.
    if (status == PersonStatus.INACTIVE && deactivatedAt == null) {
      throw new IllegalStateException("an INACTIVE person must have deactivatedAt");
    }
    if (status == PersonStatus.ACTIVE && deactivatedAt != null) {
      throw new IllegalStateException("an ACTIVE person must not have deactivatedAt");
    }
    this.deactivatedAt = deactivatedAt;
  }

  /** Constructs a brand-new Person: version 1, ACTIVE, no deactivation. */
  public static Person createNew(
      UUID personId,
      String firstName,
      String paternalSurname,
      String maternalSurname,
      InstitutionalEmail email,
      String phone,
      Instant now) {
    return new Person(
        personId,
        firstName,
        paternalSurname,
        maternalSurname,
        email,
        phone,
        PersonStatus.ACTIVE,
        1,
        now,
        now,
        null);
  }

  /**
   * Reconstructs a Person from a persisted row. No invariant re-derivation
   * beyond what the constructor already checks — this is what {@code
   * JdbcPersonRepository} uses to map a result set back into the domain.
   */
  public static Person reconstitute(
      UUID personId,
      String firstName,
      String paternalSurname,
      String maternalSurname,
      InstitutionalEmail email,
      String phone,
      PersonStatus status,
      int version,
      Instant createdAt,
      Instant updatedAt,
      Instant deactivatedAt) {
    return new Person(
        personId,
        firstName,
        paternalSurname,
        maternalSurname,
        email,
        phone,
        status,
        version,
        createdAt,
        updatedAt,
        deactivatedAt);
  }

  /**
   * Validates and computes the result of an {@code UpdatePerson} command.
   * Does not change {@code status} or {@code deactivatedAt}
   * (commands.md:117 — update never touches accounts, roles, or global
   * status).
   */
  public Person withUpdatedProfile(
      String firstName,
      String paternalSurname,
      String maternalSurname,
      InstitutionalEmail email,
      String phone,
      Instant now) {
    return new Person(
        personId,
        firstName,
        paternalSurname,
        maternalSurname,
        email,
        phone,
        status,
        version + 1,
        createdAt,
        now,
        deactivatedAt);
  }

  /**
   * Validates and computes the result of an {@code ActivatePerson}
   * command. Throws {@link RejectionCode#PERSON_ALREADY_ACTIVE} if this
   * Person is already ACTIVE (docs/system-contract.md §3 rejection
   * precedence: callers must check NOT_FOUND and VERSION_CONFLICT before
   * reaching this state check).
   */
  public Person activated(Instant now) {
    if (status == PersonStatus.ACTIVE) {
      throw new PersonRejectedException(
          RejectionCode.PERSON_ALREADY_ACTIVE, "person " + personId + " is already active");
    }
    return new Person(
        personId,
        firstName,
        paternalSurname,
        maternalSurname,
        email,
        phone,
        PersonStatus.ACTIVE,
        version + 1,
        createdAt,
        now,
        null);
  }

  /**
   * Validates and computes the result of a {@code DeactivatePerson}
   * command. Throws {@link RejectionCode#PERSON_ALREADY_INACTIVE} if this
   * Person is already INACTIVE.
   */
  public Person deactivated(Instant now) {
    if (status == PersonStatus.INACTIVE) {
      throw new PersonRejectedException(
          RejectionCode.PERSON_ALREADY_INACTIVE, "person " + personId + " is already inactive");
    }
    return new Person(
        personId,
        firstName,
        paternalSurname,
        maternalSurname,
        email,
        phone,
        PersonStatus.INACTIVE,
        version + 1,
        createdAt,
        now,
        now);
  }

  public UUID personId() {
    return personId;
  }

  public String firstName() {
    return firstName;
  }

  public String paternalSurname() {
    return paternalSurname;
  }

  public String maternalSurname() {
    return maternalSurname;
  }

  public InstitutionalEmail email() {
    return email;
  }

  public String phone() {
    return phone;
  }

  public PersonStatus status() {
    return status;
  }

  public int version() {
    return version;
  }

  public Instant createdAt() {
    return createdAt;
  }

  public Instant updatedAt() {
    return updatedAt;
  }

  public Instant deactivatedAt() {
    return deactivatedAt;
  }

  private static <T> T requireNonNull(T value, String fieldName) {
    if (value == null) {
      throw new PersonRejectedException(RejectionCode.INVALID_PERSON_DATA, fieldName + " must not be null");
    }
    return value;
  }

  private static String requireNonBlank(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new PersonRejectedException(RejectionCode.INVALID_PERSON_DATA, fieldName + " must not be blank");
    }
    return value.trim();
  }

  /**
   * {@code maternalSurname} is optional, but a present-and-blank value is
   * rejected rather than silently coerced to null — mirrors {@code
   * ck_persons_maternal_not_blank} exactly (NULL or non-blank, nothing
   * else).
   */
  private static String requireNonBlankWhenPresent(String value, String fieldName) {
    if (value == null) {
      return null;
    }
    if (value.isBlank()) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_PERSON_DATA, fieldName + " must not be blank when provided");
    }
    return value.trim();
  }

  /**
   * {@code phone} has no documented format or blankness rule
   * (data-model.md §13 lists it as pending); a blank value is treated as
   * "not provided" rather than rejected.
   */
  private static String normalizeBlankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }
}
