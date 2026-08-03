package com.estoma.users.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InstitutionalEmailTest {

  @Test
  void normalizesByTrimmingAndLowercasing() {
    InstitutionalEmail email = InstitutionalEmail.of("  Ada.Lovelace@Example.EDU  ");

    assertThat(email.value()).isEqualTo("  Ada.Lovelace@Example.EDU  ");
    assertThat(email.normalized()).isEqualTo("ada.lovelace@example.edu");
  }

  @Test
  void preservesOriginalCasingInValueButNotInNormalized() {
    InstitutionalEmail email = InstitutionalEmail.of("Person@Domain.com");

    assertThat(email.value()).isEqualTo("Person@Domain.com");
    assertThat(email.normalized()).isEqualTo("person@domain.com");
  }

  @Test
  void handlesUnicodeCharactersInLocalPart() {
    InstitutionalEmail email = InstitutionalEmail.of("Ñoño.García@Example.edu");

    assertThat(email.normalized()).isEqualTo("ñoño.garcía@example.edu");
  }

  @Test
  void normalizesInternalWhitespaceLiterallyWithoutCollapsing() {
    // Only leading/trailing whitespace is trimmed; internal whitespace (an
    // invalid email shape) is left alone for the format check to reject.
    assertThatThrownBy(() -> InstitutionalEmail.of("a b@example.edu"))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.INVALID_PERSON_DATA));
  }

  @Test
  void rejectsBlankValue() {
    assertThatThrownBy(() -> InstitutionalEmail.of("   "))
        .isInstanceOf(PersonRejectedException.class)
        .satisfies(
            e -> assertThat(((PersonRejectedException) e).code()).isEqualTo(RejectionCode.INVALID_PERSON_DATA));
  }

  @Test
  void rejectsNullValue() {
    assertThatThrownBy(() -> InstitutionalEmail.of(null))
        .isInstanceOf(PersonRejectedException.class);
  }

  @Test
  void rejectsValueWithoutAtSign() {
    assertThatThrownBy(() -> InstitutionalEmail.of("not-an-email"))
        .isInstanceOf(PersonRejectedException.class);
  }

  @Test
  void rejectsValueWithoutDomainDot() {
    assertThatThrownBy(() -> InstitutionalEmail.of("person@localhost"))
        .isInstanceOf(PersonRejectedException.class);
  }

  @Test
  void rejectsConstructionWithMismatchedNormalizedForm() {
    assertThatThrownBy(() -> new InstitutionalEmail("Person@Example.com", "wrong@example.com"))
        .isInstanceOf(IllegalStateException.class);
  }
}
