package com.estoma.users.domain;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The institutional email of a {@link Person}. Belongs to the Person, not
 * to any Account, and is never used as an Account login identifier
 * (data-model.md §6).
 *
 * <p>Normalization is exactly {@code trim + lowercase}
 * (data-model.md §5), enforced both here and by the database check
 * constraint {@code ck_persons_email_normalized} — defense in depth, since
 * the database is the final arbiter but a domain-level rejection produces
 * a much better {@code INVALID_PERSON_DATA} result than a raw constraint
 * violation surfacing as a technical failure.
 *
 * <p>Format validation is intentionally minimal: the platform docs do not
 * specify an email grammar, only the normalization rule. This only rejects
 * obviously malformed values (no {@code @}, blank local or domain part) —
 * not a full RFC 5322 implementation.
 */
public record InstitutionalEmail(String value, String normalized) {

  private static final Pattern MINIMAL_SHAPE = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

  public InstitutionalEmail {
    if (value == null || value.isBlank()) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_PERSON_DATA, "institutionalEmail must not be blank");
    }
    if (!MINIMAL_SHAPE.matcher(value.trim()).matches()) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_PERSON_DATA, "institutionalEmail is not a valid email address");
    }
    String expectedNormalized = normalize(value);
    if (!expectedNormalized.equals(normalized)) {
      throw new IllegalStateException(
          "InstitutionalEmail constructed with a normalized value out of sync with value; use InstitutionalEmail.of(...)");
    }
  }

  public static InstitutionalEmail of(String rawValue) {
    return new InstitutionalEmail(rawValue, normalize(rawValue));
  }

  static String normalize(String rawValue) {
    if (rawValue == null) {
      throw new PersonRejectedException(
          RejectionCode.INVALID_PERSON_DATA, "institutionalEmail must not be null");
    }
    return rawValue.trim().toLowerCase(Locale.ROOT);
  }
}
