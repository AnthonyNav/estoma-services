package com.estoma.appointments.infrastructure.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AppointmentQrTokenServiceTest {
  private static final String SECRET = "0123456789abcdef0123456789abcdef";

  @Test
  void issuesStableIntegrityProtectedV1Representation() {
    UUID appointmentId = UUID.randomUUID();
    var service = new AppointmentQrTokenService(SECRET);

    String first = service.issue(appointmentId);

    assertThat(first).isEqualTo(service.issue(appointmentId));
    assertThat(first).startsWith("v1." + appointmentId + ".");
    assertThat(service.verify(first)).isEqualTo(appointmentId);
    assertThatThrownBy(() -> service.verify(first.replace(
        appointmentId.toString(), UUID.randomUUID().toString())))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void activationRejectsBlankOrShortSecretsAndDisabledModeCannotIssue() {
    assertThatThrownBy(() -> new AppointmentQrTokenService(true, " ".repeat(32)))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> new AppointmentQrTokenService(true, "too-short"))
        .isInstanceOf(IllegalStateException.class);

    var disabled = new AppointmentQrTokenService(false, "");
    assertThat(disabled.enabled()).isFalse();
    assertThatThrownBy(() -> disabled.issue(UUID.randomUUID()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("disabled");
  }
}
