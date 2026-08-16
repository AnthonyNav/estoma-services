package com.estoma.authorization.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authorization.application.command.ApplicationResult;
import com.estoma.authorization.application.command.AssignAccountRoleCommand;
import com.estoma.authorization.application.command.CausalDependency;
import com.estoma.authorization.application.command.CommandMetadata;
import com.estoma.authorization.application.command.ImportExecution;
import com.estoma.authorization.application.port.CommandDispatcher;
import com.estoma.authorization.application.port.CommandIdempotencyRepository;
import com.estoma.authorization.domain.model.ActorRef;
import com.estoma.authorization.domain.model.RoleCode;
import com.estoma.authorization.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

class AuthorizationGenerationFencingIT extends AbstractPostgresIT {

  private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private CommandDispatcher commands;
  @Autowired private CommandIdempotencyRepository idempotency;
  @Autowired private JdbcClient jdbc;

  @Test
  void exactRedeliveryOfDeferredOlderGenerationBecomesTerminallyStale() {
    UUID accountId = UUID.randomUUID();
    UUID importId = UUID.randomUUID();
    UUID planItemId = UUID.randomUUID();
    UUID priorResultMessageId = UUID.randomUUID();
    UUID oldMessageId = UUID.randomUUID();
    CausalDependency dependency = new CausalDependency(
        "CreateAccount",
        priorResultMessageId,
        "SUCCEEDED",
        "AccountCreated",
        accountId,
        1);

    ImportExecution oldExecution = new ImportExecution(
        importId,
        planItemId,
        1,
        UUID.randomUUID(),
        "ASSIGN_STUDENT_ROLE",
        List.of(dependency),
        List.of());
    CommandMetadata oldMetadata = metadata(
        oldMessageId, "generation-1", priorResultMessageId);
    AssignAccountRoleCommand oldCommand =
        new AssignAccountRoleCommand(accountId, RoleCode.ALUMNO, oldExecution);

    assertThat(commands.dispatch(oldMetadata, oldCommand).deferred()).isTrue();

    ImportExecution newerExecution = new ImportExecution(
        importId,
        planItemId,
        2,
        UUID.randomUUID(),
        "ASSIGN_STUDENT_ROLE",
        List.of(dependency),
        List.of());
    assertThat(commands.dispatch(
            metadata(UUID.randomUUID(), "generation-2", priorResultMessageId),
            new AssignAccountRoleCommand(accountId, RoleCode.ALUMNO, newerExecution))
        .deferred()).isTrue();

    var stale = commands.dispatch(oldMetadata, oldCommand);

    assertThat(stale.result()).get().satisfies(result -> {
      assertThat(result.status()).isEqualTo(ApplicationResult.Status.REJECTED);
      assertThat(result.error()).extracting(ApplicationResult.Error::code)
          .isEqualTo("STALE_EXECUTION_GENERATION");
      assertThat(result.dataJson())
          .contains(importId.toString(), planItemId.toString(), "\"executionGeneration\"");
    });
    assertThat(idempotency.find("academic", "AssignAccountRole", "generation-1")).get()
        .satisfies(record -> {
          assertThat(record.status()).isEqualTo("COMPLETED");
          assertThat(record.errorCode()).isEqualTo("STALE_EXECUTION_GENERATION");
        });
    assertThat(jdbc.sql(
            "SELECT status::text FROM processed_messages "
                + "WHERE consumer_name='authorization-commands' AND message_id=:messageId")
        .param("messageId", oldMessageId)
        .query(String.class)
        .single())
        .isEqualTo("APPLIED");
  }

  private static CommandMetadata metadata(
      UUID messageId, String idempotencyKey, UUID causationId) {
    return new CommandMetadata(
        messageId,
        "AssignAccountRole",
        1,
        "academic",
        NOW,
        UUID.randomUUID(),
        causationId,
        UUID.randomUUID(),
        idempotencyKey,
        ActorRef.system("academic"),
        null,
        null);
  }
}
