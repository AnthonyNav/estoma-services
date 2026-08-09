package com.estoma.authentication.infrastructure.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers
@Tag("integration")
class RuntimeRolePrivilegesIT {

  private static final String DATABASE = "authentication_db";
  private static final String MIGRATION_USER = "migration_user";
  private static final String MIGRATION_PASSWORD = "migration_password";
  private static final String RUNTIME_USER = "runtime_user";
  private static final String RUNTIME_PASSWORD = "runtime_password";
  private static final List<String> APPLICATION_TABLES = List.of(
      "accounts",
      "account_credentials",
      "sessions",
      "password_recovery_requests",
      "account_status_history",
      "command_idempotency_records",
      "processed_messages",
      "outbox_messages",
      "student_number_login_sync_projection",
      "security_rpc_idempotency_records",
      "person_status_projection");

  @Container
  private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
      .withDatabaseName(DATABASE)
      .withUsername("postgres")
      .withPassword("postgres");

  @BeforeAll
  static void migrateWithSeparateRuntimeRole() throws SQLException {
    try (Connection admin = adminConnection()) {
      execute(admin, "REVOKE CREATE ON SCHEMA public FROM PUBLIC");
      execute(admin, "CREATE ROLE migration_user LOGIN PASSWORD 'migration_password'");
      execute(admin, "CREATE ROLE runtime_user LOGIN PASSWORD 'runtime_password'");
      execute(admin, "GRANT CONNECT ON DATABASE authentication_db TO migration_user, runtime_user");
      execute(admin, "GRANT USAGE, CREATE ON SCHEMA public TO migration_user");
      execute(admin, "GRANT USAGE ON SCHEMA public TO runtime_user");
      execute(admin, "ALTER SCHEMA public OWNER TO migration_user");
    }

    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD)
        .locations("classpath:db/migration")
        .placeholders(Map.of("runtime_role", RUNTIME_USER))
        .load()
        .migrate();
  }

  @Test
  void runtimeRoleHasApplicationDmlButCannotChangeSchemaOrFlywayHistory() throws SQLException {
    UUID accountId = createParentAccount();

    try (Connection runtime = runtimeConnection()) {
      assertRuntimeRoleIsUnprivileged(runtime);
      assertThat(queryBoolean(runtime, "SELECT has_schema_privilege(current_user, 'public', 'CREATE')")).isFalse();
      for (String table : APPLICATION_TABLES) {
        assertDmlPrivileges(runtime, table);
      }

      assertCommandIdempotencyDml(runtime, accountId);
      assertAccountStatusHistoryDml(runtime, accountId);
      assertThatThrownBy(() -> execute(runtime, "CREATE TABLE public.runtime_role_forbidden (id integer)"))
          .isInstanceOf(SQLException.class);

      assertNoFlywayHistoryWritePrivileges(runtime);
    }

    createProbeTableAsMigrationUser();

    try (Connection runtime = runtimeConnection()) {
      assertDmlPrivileges(runtime, "runtime_privilege_probe");
      execute(runtime, "INSERT INTO public.runtime_privilege_probe (id, value) VALUES (1, 'created')");
      assertThat(queryString(runtime, "SELECT value FROM public.runtime_privilege_probe WHERE id = 1")).isEqualTo("created");
      execute(runtime, "UPDATE public.runtime_privilege_probe SET value = 'updated' WHERE id = 1");
      execute(runtime, "DELETE FROM public.runtime_privilege_probe WHERE id = 1");
    }
  }

  private static UUID createParentAccount() throws SQLException {
    UUID accountId = UUID.randomUUID();
    try (Connection migration = migrationConnection();
        PreparedStatement statement = migration.prepareStatement("""
            INSERT INTO public.accounts (
                account_id, person_id, login_type, login_identifier, login_identifier_normalized,
                status, created_at, updated_at
            ) VALUES (?, ?, 'USERNAME', ?, ?, 'ACTIVE', now(), now())
            """)) {
      statement.setObject(1, accountId);
      statement.setObject(2, UUID.randomUUID());
      statement.setString(3, "runtime-" + accountId);
      statement.setString(4, "runtime-" + accountId);
      statement.executeUpdate();
    }
    return accountId;
  }

  private static void assertCommandIdempotencyDml(Connection runtime, UUID accountId) throws SQLException {
    String key = "runtime-" + UUID.randomUUID();
    try (PreparedStatement statement = runtime.prepareStatement("""
        INSERT INTO public.command_idempotency_records (
            producer, operation_type, idempotency_key, request_fingerprint, target_account_id, status, created_at
        ) VALUES ('runtime-test', 'TEST', ?, 'fingerprint', ?, 'PROCESSING', now())
        """)) {
      statement.setString(1, key);
      statement.setObject(2, accountId);
      statement.executeUpdate();
    }
    assertThat(queryString(runtime, "SELECT status FROM public.command_idempotency_records WHERE idempotency_key = ?", key))
        .isEqualTo("PROCESSING");
    execute(runtime, "UPDATE public.command_idempotency_records SET status = 'COMPLETED' WHERE idempotency_key = ?", key);
    execute(runtime, "DELETE FROM public.command_idempotency_records WHERE idempotency_key = ?", key);
  }

  private static void assertAccountStatusHistoryDml(Connection runtime, UUID accountId) throws SQLException {
    UUID historyId = UUID.randomUUID();
    try (PreparedStatement statement = runtime.prepareStatement("""
        INSERT INTO public.account_status_history (
            history_id, account_id, new_status, actor_type, correlation_id, occurred_at, account_version
        ) VALUES (?, ?, 'ACTIVE', 'SERVICE', ?, now(), 1)
        """)) {
      statement.setObject(1, historyId);
      statement.setObject(2, accountId);
      statement.setObject(3, UUID.randomUUID());
      statement.executeUpdate();
    }
    assertThat(queryString(runtime, "SELECT new_status::text FROM public.account_status_history WHERE history_id = ?", historyId))
        .isEqualTo("ACTIVE");
    execute(runtime, "UPDATE public.account_status_history SET actor_service = 'runtime-test' WHERE history_id = ?", historyId);
    execute(runtime, "DELETE FROM public.account_status_history WHERE history_id = ?", historyId);
  }

  private static void assertNoFlywayHistoryWritePrivileges(Connection runtime) throws SQLException {
    for (String privilege : List.of("INSERT", "UPDATE", "DELETE")) {
      assertThat(hasTablePrivilege(runtime, "flyway_schema_history", privilege)).isFalse();
    }
    assertThatThrownBy(() -> execute(runtime, "INSERT INTO public.flyway_schema_history DEFAULT VALUES"))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> execute(runtime, "UPDATE public.flyway_schema_history SET success = success"))
        .isInstanceOf(SQLException.class);
    assertThatThrownBy(() -> execute(runtime, "DELETE FROM public.flyway_schema_history"))
        .isInstanceOf(SQLException.class);
  }

  private static void createProbeTableAsMigrationUser() throws SQLException {
    try (Connection migration = migrationConnection()) {
      execute(migration, "CREATE TABLE public.runtime_privilege_probe (id integer PRIMARY KEY, value varchar(40) NOT NULL)");
    }
  }

  private static void assertDmlPrivileges(Connection connection, String table) throws SQLException {
    for (String privilege : List.of("SELECT", "INSERT", "UPDATE", "DELETE")) {
      assertThat(hasTablePrivilege(connection, table, privilege)).isTrue();
    }
  }

  private static void assertRuntimeRoleIsUnprivileged(Connection runtime) throws SQLException {
    assertThat(queryBoolean(runtime, """
        SELECT rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls
        FROM pg_roles WHERE rolname = ?
        """, RUNTIME_USER)).isFalse();
    assertThat(queryBoolean(runtime, "SELECT rolcanlogin FROM pg_roles WHERE rolname = ?", RUNTIME_USER)).isTrue();
    assertThat(queryBoolean(runtime, """
        SELECT EXISTS (
            SELECT 1 FROM pg_auth_members membership
            JOIN pg_roles member ON member.oid = membership.member
            WHERE member.rolname = ?
        )
        """, RUNTIME_USER)).isFalse();
    assertThat(queryBoolean(runtime, """
        SELECT EXISTS (
            SELECT 1 FROM pg_class relation
            JOIN pg_namespace namespace ON namespace.oid = relation.relnamespace
            JOIN pg_roles owner ON owner.oid = relation.relowner
            WHERE namespace.nspname = 'public'
              AND relation.relkind = 'r'
              AND owner.rolname = ?
        )
        """, RUNTIME_USER)).isFalse();
    assertThat(queryBoolean(runtime, """
        SELECT owner.rolname = ?
        FROM pg_namespace namespace
        JOIN pg_roles owner ON owner.oid = namespace.nspowner
        WHERE namespace.nspname = 'public'
        """, RUNTIME_USER)).isFalse();
  }

  private static boolean hasTablePrivilege(Connection connection, String table, String privilege) throws SQLException {
    return queryBoolean(connection, "SELECT has_table_privilege(current_user, ?, ?)", "public." + table, privilege);
  }

  private static Connection adminConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
  }

  private static Connection migrationConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), MIGRATION_USER, MIGRATION_PASSWORD);
  }

  private static Connection runtimeConnection() throws SQLException {
    return DriverManager.getConnection(POSTGRES.getJdbcUrl(), RUNTIME_USER, RUNTIME_PASSWORD);
  }

  private static void execute(Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      for (int index = 0; index < parameters.length; index++) {
        statement.setObject(index + 1, parameters[index]);
      }
      statement.executeUpdate();
    }
  }

  private static boolean queryBoolean(Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getBoolean(1);
      }
    }
  }

  private static String queryString(Connection connection, String sql, Object... parameters) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      bind(statement, parameters);
      try (ResultSet result = statement.executeQuery()) {
        result.next();
        return result.getString(1);
      }
    }
  }

  private static void bind(PreparedStatement statement, Object... parameters) throws SQLException {
    for (int index = 0; index < parameters.length; index++) {
      statement.setObject(index + 1, parameters[index]);
    }
  }
}
