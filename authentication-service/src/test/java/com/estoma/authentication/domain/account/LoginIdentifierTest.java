package com.estoma.authentication.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authentication.contracts.LoginType;
import org.junit.jupiter.api.Test;

class LoginIdentifierTest {

  @Test
  void normalizesByTrimmingAndLowercasing() {
    LoginIdentifier identifier = LoginIdentifier.of(LoginType.USERNAME, "  Jane.Doe  ");

    assertThat(identifier.value()).isEqualTo("Jane.Doe");
    assertThat(identifier.normalized()).isEqualTo("jane.doe");
  }

  @Test
  void rejectsBlankValue() {
    assertThatThrownBy(() -> LoginIdentifier.of(LoginType.MATRICULA, "   "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullType() {
    assertThatThrownBy(() -> LoginIdentifier.of(null, "12345"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
