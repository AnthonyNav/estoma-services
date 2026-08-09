package com.estoma.authentication.infrastructure.persistence;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.domain.AuthenticationRejectedException;
import com.estoma.authentication.domain.RejectionCode;
import com.estoma.authentication.domain.account.Account;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC implementation of {@link AccountRepository}. Hand-written SQL, not
 * JPA -- see system_contract.md "Internal Architecture" for why: the
 * client-supplied {@code expectedVersion} guard below needs a real {@code
 * WHERE version = ?} that Hibernate's {@code @Version} cannot express.
 *
 * <p>{@link #insert} and {@link #update} run under {@code
 * Propagation.NESTED}: PostgreSQL aborts the entire enclosing transaction
 * the instant a statement fails (unlike some other databases), so without a
 * savepoint here, a dispatcher's later attempt to append a Rejected result
 * in the same transaction -- right after catching the translated {@link
 * AuthenticationRejectedException} -- would itself fail with "current
 * transaction is aborted". See {@code infrastructure.config.TransactionManagerConfig}.
 */
@Repository
class JdbcAccountRepository implements AccountRepository {

  private static final String COLUMNS =
      """
      account_id, person_id, login_type, login_identifier, login_identifier_normalized,
       status, failed_attempts, locked_until,
       version, created_at, updated_at, deactivated_at, deactivation_reason_code, last_recovery_requested_at
      """;

  private final JdbcClient jdbc;

  JdbcAccountRepository(JdbcClient jdbc) {
    this.jdbc = jdbc;
  }

  @Override
  public Optional<Account> findById(UUID accountId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE account_id = :accountId")
        .param("accountId", accountId)
        .query(AccountRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<Account> findByIdForUpdate(UUID accountId) {
    return jdbc.sql("SELECT " + COLUMNS + " FROM accounts WHERE account_id = :accountId FOR UPDATE")
        .param("accountId", accountId)
        .query(AccountRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<Account> findByLoginIdentifier(LoginType loginType, String normalizedIdentifier) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM accounts WHERE login_type = CAST(:loginType AS login_type) "
                + "AND login_identifier_normalized = :normalized")
        .param("loginType", loginType.name())
        .param("normalized", normalizedIdentifier)
        .query(AccountRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public Optional<Account> findByLoginIdentifierForUpdate(LoginType loginType, String normalizedIdentifier) {
    return jdbc.sql(
            "SELECT " + COLUMNS + " FROM accounts WHERE login_type = CAST(:loginType AS login_type) "
                + "AND login_identifier_normalized = :normalized FOR UPDATE")
        .param("loginType", loginType.name())
        .param("normalized", normalizedIdentifier)
        .query(AccountRowMapper.INSTANCE)
        .optional();
  }

  @Override
  public List<UUID> findIdsByPersonId(UUID personId) {
    return jdbc.sql("SELECT account_id FROM accounts WHERE person_id = :personId")
        .param("personId", personId)
        .query(JdbcAccountRepository::mapAccountId)
        .list();
  }

  @Override
  @Transactional(propagation = Propagation.NESTED)
  public void insert(Account account) {
    try {
      jdbc.sql(
              """
              INSERT INTO accounts (
                account_id, person_id, login_type, login_identifier, login_identifier_normalized,
                 status, failed_attempts, locked_until,
                 version, created_at, updated_at, deactivated_at, deactivation_reason_code, last_recovery_requested_at
              ) VALUES (
                :accountId, :personId, CAST(:loginType AS login_type), :loginIdentifier, :normalized,
                 CAST(:status AS account_status), :failedAttempts, :lockedUntil,
                 :version, :createdAt, :updatedAt, :deactivatedAt, :deactivationReasonCode, :lastRecoveryRequestedAt
              )
              """)
          .param("accountId", account.accountId())
          .param("personId", account.personId())
          .param("loginType", account.loginIdentifier().type().name())
          .param("loginIdentifier", account.loginIdentifier().value())
          .param("normalized", account.loginIdentifier().normalized())
          .param("status", account.status().name())
          .param("failedAttempts", account.failedAttempts())
          .param("lockedUntil", Timestamps.toTimestamp(account.lockedUntil()))
          .param("version", account.version())
          .param("createdAt", Timestamps.toTimestamp(account.createdAt()))
          .param("updatedAt", Timestamps.toTimestamp(account.updatedAt()))
          .param("deactivatedAt", Timestamps.toTimestamp(account.deactivatedAt()))
          .param("deactivationReasonCode", account.deactivationReasonCode())
          .param("lastRecoveryRequestedAt", Timestamps.toTimestamp(account.lastRecoveryRequestedAt()))
          .update();
    } catch (DuplicateKeyException e) {
      throw translateInsertConflict(e);
    }
  }

  @Override
  @Transactional(propagation = Propagation.NESTED)
  public int update(Account updated, int expectedVersion) {
    try {
      return jdbc.sql(
              """
              UPDATE accounts SET
                login_type = CAST(:loginType AS login_type),
                login_identifier = :loginIdentifier,
                login_identifier_normalized = :normalized,
                status = CAST(:status AS account_status),
                failed_attempts = :failedAttempts,
                 locked_until = :lockedUntil,
                version = :newVersion,
                 updated_at = :updatedAt,
                 deactivated_at = :deactivatedAt,
                 deactivation_reason_code = :deactivationReasonCode,
                 last_recovery_requested_at = :lastRecoveryRequestedAt
              WHERE account_id = :accountId AND version = :expectedVersion
              """)
          .param("loginType", updated.loginIdentifier().type().name())
          .param("loginIdentifier", updated.loginIdentifier().value())
          .param("normalized", updated.loginIdentifier().normalized())
          .param("status", updated.status().name())
          .param("failedAttempts", updated.failedAttempts())
          .param("lockedUntil", Timestamps.toTimestamp(updated.lockedUntil()))
          .param("newVersion", updated.version())
          .param("updatedAt", Timestamps.toTimestamp(updated.updatedAt()))
          .param("deactivatedAt", Timestamps.toTimestamp(updated.deactivatedAt()))
          .param("deactivationReasonCode", updated.deactivationReasonCode())
          .param("lastRecoveryRequestedAt", Timestamps.toTimestamp(updated.lastRecoveryRequestedAt()))
          .param("accountId", updated.accountId())
          .param("expectedVersion", expectedVersion)
          .update();
    } catch (DuplicateKeyException e) {
      throw new AuthenticationRejectedException(
          RejectionCode.LOGIN_IDENTIFIER_ALREADY_USED, "login identifier is already claimed by a different account");
    }
  }

  private static AuthenticationRejectedException translateInsertConflict(DuplicateKeyException e) {
    String message = e.getMostSpecificCause().getMessage();
    if (message != null && message.contains("accounts_pkey")) {
      return new AuthenticationRejectedException(RejectionCode.ACCOUNT_ALREADY_EXISTS, "an account with this accountId already exists");
    }
    if (message != null && message.contains("uq_accounts_login")) {
      return new AuthenticationRejectedException(
          RejectionCode.LOGIN_IDENTIFIER_ALREADY_USED, "login identifier is already claimed by a different account");
    }
    throw e;
  }

  private static UUID mapAccountId(ResultSet rs, int rowNum) throws SQLException {
    return rs.getObject("account_id", UUID.class);
  }
}
