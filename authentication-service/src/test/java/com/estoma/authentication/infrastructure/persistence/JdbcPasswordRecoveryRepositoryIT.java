package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PasswordRecoveryRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.domain.recovery.PasswordRecoveryRequest;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class JdbcPasswordRecoveryRepositoryIT extends AbstractPostgresIT {

  @Autowired private AccountRepository accountRepository;
  @Autowired private PasswordRecoveryRepository repository;
  @Autowired private JdbcClient jdbc;

  @Test
  void markUsedIfValidSucceedsOnce() {
    UUID accountId = insertAccount();
    PasswordRecoveryRequest request = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, Instant.now(), Instant.now().plusSeconds(1800));
    repository.insert(request);

    var persisted = repository.findById(request.recoveryId()).orElseThrow();
    assertThat(persisted.codeDerivationContext()).isEqualTo("HMAC_SHA256_V1");
    assertThat(jdbc.sql("SELECT token_hash FROM password_recovery_requests WHERE recovery_id = :recoveryId")
        .param("recoveryId", request.recoveryId()).query(String.class).optional()).isEmpty();

    int first = repository.markUsedIfValid(request.recoveryId(), Instant.now(), Instant.now());
    int second = repository.markUsedIfValid(request.recoveryId(), Instant.now(), Instant.now());

    assertThat(first).isEqualTo(1);
    assertThat(second).isZero();
  }

  @Test
  void markUsedIfValidFailsWhenExpired() {
    UUID accountId = insertAccount();
    Instant past = Instant.now().minusSeconds(10);
    PasswordRecoveryRequest request = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, past.minusSeconds(3600), past);
    repository.insert(request);

    int rowsAffected = repository.markUsedIfValid(request.recoveryId(), Instant.now(), Instant.now());

    assertThat(rowsAffected).isZero();
  }

  @Test
  void failedAttemptsTransitionToAttemptsExhaustedOnTheFifthAttempt() {
    UUID accountId = insertAccount();
    PasswordRecoveryRequest request = PasswordRecoveryRequest.createNew(UUID.randomUUID(), accountId, Instant.now(), Instant.now().plusSeconds(1800));
    repository.insert(request);

    for (int attempt = 0; attempt < 5; attempt++) {
      repository.recordFailedAttempt(request.recoveryId(), Instant.now(), 5);
    }

    var persisted = repository.findById(request.recoveryId()).orElseThrow();
    assertThat(persisted.failedAttempts()).isEqualTo(5);
    assertThat(persisted.status()).isEqualTo(PasswordRecoveryRequest.Status.ATTEMPTS_EXHAUSTED);
  }

  private UUID insertAccount() {
    Account account = Account.createNew(
        UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, "rec-" + UUID.randomUUID()), Instant.now());
    accountRepository.insert(account);
    return account.accountId();
  }
}
