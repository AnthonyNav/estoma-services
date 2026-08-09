package com.estoma.authentication.domain.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.authentication.contracts.AccountStatus;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private final Instant now = Instant.parse("2026-01-01T00:00:00Z");
  private final LoginIdentifier loginIdentifier = LoginIdentifier.of(LoginType.MATRICULA, "A0001");

  @Test
  void createNewIsInactiveWithVersionOne() {
    Account account = Account.createNew(UUID.randomUUID(), UUID.randomUUID(), loginIdentifier, now);

    assertThat(account.status()).isEqualTo(AccountStatus.INACTIVE);
    assertThat(account.version()).isEqualTo(1);
    assertThat(account.failedAttempts()).isZero();
    assertThat(account.deactivatedAt()).isNull();
  }

  @Test
  void activatedMovesInactiveToActiveAndBumpsVersion() {
    Account account = newInactiveAccount();

    Account activated = account.activated(now.plusSeconds(60));

    assertThat(activated.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(activated.version()).isEqualTo(account.version() + 1);
  }

  @Test
  void activatingAnAlreadyActiveAccountIsRejected() {
    Account account = newInactiveAccount().activated(now);

    assertThatThrownBy(() -> account.activated(now))
        .isInstanceOf(AuthenticationRejectedException.class)
        .satisfies(e -> assertThat(((AuthenticationRejectedException) e).code()).isEqualTo(RejectionCode.ACCOUNT_ALREADY_ACTIVE));
  }

  @Test
  void deactivatedSetsInactiveAndDeactivatedAt() {
    Account account = newInactiveAccount().activated(now);

    Account deactivated = account.deactivated(now.plusSeconds(120));

    assertThat(deactivated.status()).isEqualTo(AccountStatus.INACTIVE);
    assertThat(deactivated.deactivatedAt()).isEqualTo(now.plusSeconds(120));
  }

  @Test
  void failedAttemptIncrementsAndCanCarryALock() {
    Account account = newInactiveAccount().activated(now);
    Instant lockUntil = now.plus(15, ChronoUnit.MINUTES);

    Account locked = account.withFailedAttemptRecorded(now, lockUntil);

    assertThat(locked.failedAttempts()).isEqualTo(1);
    assertThat(locked.isLocked(now.plusSeconds(1))).isTrue();
    assertThat(locked.isLocked(lockUntil.plusSeconds(1))).isFalse();
  }

  @Test
  void attemptsResetClearsFailedAttemptsAndLock() {
    Account locked = newInactiveAccount().activated(now).withFailedAttemptRecorded(now, now.plusSeconds(900));

    Account reset = locked.withAttemptsReset(now);

    assertThat(reset.failedAttempts()).isZero();
    assertThat(reset.isLocked(now)).isFalse();
  }

  private Account newInactiveAccount() {
    return Account.createNew(UUID.randomUUID(), UUID.randomUUID(), loginIdentifier, now);
  }
}
