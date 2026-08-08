package com.estoma.users.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the mandatory migration scenario from
 * estomatologia-platform-docs 02-architecture/messaging-architecture.md
 * §17 test list and data-consistency-architecture.md: migrations apply
 * cleanly, are repeatable/no-op on a clean schema, and every declared check
 * constraint actually rejects the row it exists to reject.
 *
 * <p>Flyway itself already ran once during Spring context startup (Flyway
 * auto-configuration migrates on the primary DataSource by default), so
 * this test's job is to assert the *result* of that run and to prove a
 * second {@code migrate()} call is a true no-op — which is what protects a
 * multi-replica rollout from re-applying the same migration twice
 * (deployment-architecture.md:537).
 */
class FlywayMigrationIT extends AbstractPostgresIT {

  @Autowired private Flyway flyway;
  @Autowired private JdbcClient jdbc;

  @Test
  void allMigrationsAppliedSuccessfullyOnContextStartup() {
    MigrationInfo[] applied = flyway.info().applied();

    assertThat(applied).hasSize(7);
    assertThat(applied)
        .extracting(MigrationInfo::getVersion)
        .extracting(Object::toString)
        .containsExactly("1", "2", "3", "4", "5", "6", "7");
    assertThat(applied).allSatisfy(info -> assertThat(info.getState().isFailed()).isFalse());
  }

  @Test
  void validateDoesNotThrowOnAppliedSchema() {
    flyway.validate();
  }

  @Test
  void secondMigrateCallIsANoOp() {
    int migrationsJustApplied = flyway.migrate().migrationsExecuted;

    assertThat(migrationsJustApplied).isZero();
  }

  @Test
  void rejectsBlankFirstName() {
    assertThatThrownBy(() -> insertPerson(p -> p.firstName = "   "))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_persons_first_name_not_blank");
  }

  @Test
  void rejectsBlankPaternalSurname() {
    assertThatThrownBy(() -> insertPerson(p -> p.paternalSurname = ""))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_persons_paternal_not_blank");
  }

  @Test
  void rejectsBlankMaternalSurnameWhenPresent() {
    assertThatThrownBy(() -> insertPerson(p -> p.maternalSurname = "   "))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_persons_maternal_not_blank");
  }

  @Test
  void rejectsNonPositiveVersion() {
    assertThatThrownBy(() -> insertPerson(p -> p.version = 0))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_persons_version_positive");
  }

  @Test
  void rejectsMismatchedNormalizedEmail() {
    assertThatThrownBy(
            () ->
                insertPerson(
                    p -> {
                      p.email = "Someone@Example.com";
                      p.emailNormalized = "not-the-normalized-form@example.com";
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_persons_email_normalized");
  }

  @Test
  void rejectsInactiveWithoutDeactivatedAt() {
    assertThatThrownBy(
            () ->
                insertPerson(
                    p -> {
                      p.status = "INACTIVE";
                      p.deactivatedAt = null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_persons_deactivation_state");
  }

  @Test
  void rejectsActiveWithDeactivatedAt() {
    assertThatThrownBy(
            () ->
                insertPerson(
                    p -> {
                      p.status = "ACTIVE";
                      p.deactivatedAt = OffsetDateTime.now();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_persons_deactivation_state");
  }

  @Test
  void rejectsDuplicateNormalizedEmail() {
    String sharedEmail = "shared.person." + UUID.randomUUID() + "@example.edu";
    insertPerson(
        p -> {
          p.email = sharedEmail;
          p.emailNormalized = sharedEmail.toLowerCase();
        });

    assertThatThrownBy(
            () ->
                insertPerson(
                    p -> {
                      p.email = sharedEmail;
                      p.emailNormalized = sharedEmail.toLowerCase();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ux_persons_email_normalized");
  }

  @Test
  void ckOutboxClaimStateRejectsProcessingWithoutAClaimToken() {
    assertThatThrownBy(
            () ->
                insertOutboxRow(
                    r -> {
                      r.status = "PROCESSING";
                      r.claimToken = null;
                      r.leaseUntil = OffsetDateTime.now().plusMinutes(2);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_claim_state");
  }

  @Test
  void ckOutboxClaimStateRejectsProcessingWithoutALeaseUntil() {
    assertThatThrownBy(
            () ->
                insertOutboxRow(
                    r -> {
                      r.status = "PROCESSING";
                      r.claimToken = UUID.randomUUID();
                      r.leaseUntil = null;
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_claim_state");
  }

  @Test
  void ckOutboxClaimStateRejectsPendingWithAClaimToken() {
    assertThatThrownBy(
            () ->
                insertOutboxRow(
                    r -> {
                      r.status = "PENDING";
                      r.claimToken = UUID.randomUUID();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_claim_state");
  }

  @Test
  void ckOutboxClaimStateRejectsPendingWithALeaseUntil() {
    assertThatThrownBy(
            () ->
                insertOutboxRow(
                    r -> {
                      r.status = "PENDING";
                      r.leaseUntil = OffsetDateTime.now().plusMinutes(2);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_claim_state");
  }

  @Test
  void ckOutboxClaimStateRejectsPublishedWithAClaimToken() {
    assertThatThrownBy(
            () ->
                insertOutboxRow(
                    r -> {
                      r.status = "PUBLISHED";
                      r.claimToken = UUID.randomUUID();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_claim_state");
  }

  @Test
  void ckOutboxClaimStateRejectsPublishedWithALeaseUntil() {
    assertThatThrownBy(
            () ->
                insertOutboxRow(
                    r -> {
                      r.status = "PUBLISHED";
                      r.leaseUntil = OffsetDateTime.now().plusMinutes(2);
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_claim_state");
  }

  @Test
  void ckOutboxClaimStateRejectsFailedRetainingAClaimToken() {
    assertThatThrownBy(
            () ->
                insertOutboxRow(
                    r -> {
                      r.status = "FAILED";
                      r.claimToken = UUID.randomUUID();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_outbox_claim_state");
  }

  @Test
  void ckOutboxClaimStateAcceptsEveryValidStatusShape() {
    insertOutboxRow(r -> r.status = "PENDING");
    insertOutboxRow(
        r -> {
          r.status = "PROCESSING";
          r.claimToken = UUID.randomUUID();
          r.leaseUntil = OffsetDateTime.now().plusMinutes(2);
        });
    insertOutboxRow(r -> r.status = "PUBLISHED");
    insertOutboxRow(r -> r.status = "FAILED");
  }

  @Test
  void ckIdempotencyTerminalFieldsRejectsSucceededWithoutAggregateVersion() {
    assertThatThrownBy(
            () ->
                insertIdempotencyRecord(
                    r -> {
                      r.terminalStatus = "SUCCEEDED";
                      r.aggregateVersion = null;
                      r.completedAt = OffsetDateTime.now();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_idempotency_terminal_fields");
  }

  @Test
  void ckIdempotencyTerminalFieldsRejectsRejectedWithoutErrorCode() {
    assertThatThrownBy(
            () ->
                insertIdempotencyRecord(
                    r -> {
                      r.terminalStatus = "REJECTED";
                      r.errorCode = null;
                      r.completedAt = OffsetDateTime.now();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_idempotency_terminal_fields");
  }

  @Test
  void ckIdempotencyTerminalFieldsRejectsInProgressWithACompletedAt() {
    assertThatThrownBy(
            () ->
                insertIdempotencyRecord(
                    r -> {
                      r.terminalStatus = "IN_PROGRESS";
                      r.completedAt = OffsetDateTime.now();
                    }))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_idempotency_terminal_fields");
  }

  @Test
  void ckIdempotencyTerminalFieldsAcceptsEveryValidStatusShape() {
    insertIdempotencyRecord(r -> {});
    insertIdempotencyRecord(
        r -> {
          r.terminalStatus = "SUCCEEDED";
          r.aggregateVersion = 1;
          r.completedAt = OffsetDateTime.now();
        });
    insertIdempotencyRecord(
        r -> {
          r.terminalStatus = "REJECTED";
          r.errorCode = "PERSON_NOT_FOUND";
          r.completedAt = OffsetDateTime.now();
        });
  }

  @Test
  void personIdIsImmutable() {
    UUID personId = UUID.randomUUID();
    insertPerson(personId, p -> {});

    assertThatThrownBy(
            () ->
                jdbc.sql("UPDATE persons SET person_id = ? WHERE person_id = ?")
                    .params(UUID.randomUUID(), personId)
                    .update())
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("person_id is immutable");
  }

  private void insertPerson(java.util.function.Consumer<TestPersonRow> customizer) {
    insertPerson(UUID.randomUUID(), customizer);
  }

  private void insertPerson(UUID personId, java.util.function.Consumer<TestPersonRow> customizer) {
    TestPersonRow row = new TestPersonRow();
    row.email = "person." + personId + "@example.edu";
    row.emailNormalized = row.email.toLowerCase();
    customizer.accept(row);

    OffsetDateTime now = OffsetDateTime.now();
    jdbc.sql(
            """
            INSERT INTO persons (
              person_id, first_name, paternal_surname, maternal_surname,
              institutional_email, institutional_email_normalized, phone,
              status, version, created_at, updated_at, deactivated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?::person_status, ?, ?, ?, ?)
            """)
        .params(
            personId,
            row.firstName,
            row.paternalSurname,
            row.maternalSurname,
            row.email,
            row.emailNormalized,
            row.phone,
            row.status,
            row.version,
            now,
            now,
            row.deactivatedAt)
        .update();
  }

  /** Mutable bag of column values for one test row, with valid defaults. */
  private static final class TestPersonRow {
    String firstName = "Ada";
    String paternalSurname = "Lovelace";
    String maternalSurname = null;
    String email;
    String emailNormalized;
    String phone = null;
    String status = "ACTIVE";
    int version = 1;
    OffsetDateTime deactivatedAt = null;
  }

  private void insertOutboxRow(java.util.function.Consumer<TestOutboxRow> customizer) {
    TestOutboxRow row = new TestOutboxRow();
    customizer.accept(row);

    jdbc.sql(
            """
            INSERT INTO outbox_messages (
              message_id, message_type, message_version, message_kind,
              aggregate_id, aggregate_version, correlation_id, causation_id,
              payload, metadata, status, attempts, claim_token, lease_until,
              last_error_code, last_error_at, occurred_at, available_at, published_at
            ) VALUES (
              ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), NULL, ?::outbox_status, ?, ?, ?, ?, ?, ?, ?, ?
            )
            """)
        .params(
            UUID.randomUUID(),
            "PersonCreated",
            1,
            "EVENT",
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            (UUID) null,
            "{}",
            row.status,
            0,
            row.claimToken,
            row.leaseUntil,
            (String) null,
            (OffsetDateTime) null,
            OffsetDateTime.now(),
            OffsetDateTime.now(),
            (OffsetDateTime) null)
        .update();
  }

  /** Mutable bag of column values for one ck_outbox_claim_state test row. */
  private static final class TestOutboxRow {
    String status = "PENDING";
    UUID claimToken = null;
    OffsetDateTime leaseUntil = null;
  }

  private void insertIdempotencyRecord(java.util.function.Consumer<TestIdempotencyRow> customizer) {
    TestIdempotencyRow row = new TestIdempotencyRow();
    customizer.accept(row);

    jdbc.sql(
            """
            INSERT INTO idempotency_records (
              producer, idempotency_key, message_type, aggregate_id, request_fingerprint,
              claimed_by_message_id, terminal_status, aggregate_version, error_code,
              created_at, completed_at
            ) VALUES (
              ?, ?, ?, ?, ?, ?, ?::idempotency_status, ?, ?, ?, ?
            )
            """)
        .params(
            "bff",
            UUID.randomUUID().toString(),
            "CreatePerson",
            UUID.randomUUID(),
            "f".repeat(64),
            UUID.randomUUID(),
            row.terminalStatus,
            row.aggregateVersion,
            row.errorCode,
            OffsetDateTime.now(),
            row.completedAt)
        .update();
  }

  /** Mutable bag of column values for one ck_idempotency_terminal_fields test row. */
  private static final class TestIdempotencyRow {
    String terminalStatus = "IN_PROGRESS";
    Integer aggregateVersion = null;
    String errorCode = null;
    OffsetDateTime completedAt = null;
  }
}
