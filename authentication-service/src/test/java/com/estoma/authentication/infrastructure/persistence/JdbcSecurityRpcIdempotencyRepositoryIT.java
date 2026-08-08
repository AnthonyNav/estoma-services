package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.SecurityRpcIdempotencyRepository;
import com.estoma.authentication.application.port.SecurityRpcIdempotencyRepository.AlreadyCompleted;
import com.estoma.authentication.application.port.SecurityRpcIdempotencyRepository.Claimed;
import com.estoma.authentication.application.port.SecurityRpcIdempotencyRepository.FingerprintMismatch;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.account.Account;
import com.estoma.authentication.domain.account.LoginIdentifier;
import com.estoma.authentication.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class JdbcSecurityRpcIdempotencyRepositoryIT extends AbstractPostgresIT {

  @Autowired private AccountRepository accountRepository;
  @Autowired private SecurityRpcIdempotencyRepository repository;

  @Test
  void firstClaimSucceeds() {
    var result = claim("SET_TEMPORARY_PASSWORD", "key-1", "fp-1");

    assertThat(result).isInstanceOf(Claimed.class);
  }

  @Test
  void claimAfterCompletedIsUnrecoverable() {
    claim("SET_TEMPORARY_PASSWORD", "key-2", "fp-2");
    repository.markCompleted("SET_TEMPORARY_PASSWORD", "key-2", Instant.now());

    var result = claim("SET_TEMPORARY_PASSWORD", "key-2", "fp-2");

    assertThat(result).isInstanceOf(AlreadyCompleted.class);
  }

  @Test
  void sameKeyDifferentFingerprintIsRejected() {
    claim("SET_TEMPORARY_PASSWORD", "key-3", "fp-3");

    var result = claim("SET_TEMPORARY_PASSWORD", "key-3", "different-fingerprint");

    assertThat(result).isInstanceOf(FingerprintMismatch.class);
  }

  @Test
  void markFailedThenReclaimReturnsSameErrorCode() {
    claim("REGENERATE_TEMPORARY_PASSWORD", "key-4", "fp-4");
    repository.markFailed("REGENERATE_TEMPORARY_PASSWORD", "key-4", "ACCOUNT_NOT_FOUND", Instant.now());

    var result = claim("REGENERATE_TEMPORARY_PASSWORD", "key-4", "fp-4");

    assertThat(result).isInstanceOf(SecurityRpcIdempotencyRepository.AlreadyFailed.class);
    assertThat(((SecurityRpcIdempotencyRepository.AlreadyFailed) result).errorCode()).isEqualTo("ACCOUNT_NOT_FOUND");
  }

  private SecurityRpcIdempotencyRepository.ClaimResult claim(String operationType, String key, String fingerprint) {
    return repository.tryClaim(operationType, key, insertAccount(), UUID.randomUUID(), fingerprint, Instant.now().plusSeconds(86400));
  }

  private UUID insertAccount() {
    Account account = Account.createNew(
        UUID.randomUUID(), UUID.randomUUID(), LoginIdentifier.of(LoginType.MATRICULA, "idem-" + UUID.randomUUID()), Instant.now());
    accountRepository.insert(account);
    return account.accountId();
  }
}
