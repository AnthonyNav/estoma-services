package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.command.ActivatePersonCommand;
import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.application.command.DeactivatePersonCommand;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.testsupport.AbstractPostgresIT;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.JsonNode;

/**
 * {@link CommandTransactionIT} proves the {@code {persons, processed_messages,
 * outbox_messages}} commit atomicity for {@code CreatePerson} against a real
 * database. This class does the same for {@code person_status_history}
 * (data-model.md §7: every activation/deactivation must update {@code
 * persons}, increment {@code version}, insert {@code person_status_history},
 * and register the outbox event, all in one transaction) — a real gap
 * before this test, since {@code ActivatePersonHandlerTest}/
 * {@code DeactivatePersonHandlerTest} only verify the handler calls a mocked
 * {@code PersonStatusHistoryRepository}, never the real JDBC insert.
 */
class PersonStatusHistoryTransactionIT extends AbstractPostgresIT {

  @Autowired private PersonCommandDispatcher dispatcher;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private JdbcClient jdbc;

  @Test
  void deactivateThenActivateEachCommitExactlyOneAppendOnlyHistoryRowInTheSameTransaction() {
    UUID personId = UUID.randomUUID();
    UUID actorAccountId = UUID.randomUUID();
    createPerson(personId, actorAccountId);

    UUID deactivateCorrelationId = UUID.randomUUID();
    CommandOutcome deactivateOutcome =
        dispatcher.dispatch(
            envelope(
                MessageTypes.DEACTIVATE_PERSON,
                deactivateCorrelationId,
                actorAccountId,
                codec.toJsonNode(new DeactivatePersonCommand(personId, 1, "ADMINISTRATIVE_REQUEST", null))));

    assertThat(deactivateOutcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(((CommandOutcome.Succeeded) deactivateOutcome).aggregateVersion()).isEqualTo(2);
    assertThat(countWhere("person_status_history", "person_id = :id", personId)).isEqualTo(1);
    assertHistoryRow(
        personId, "ACTIVE", "INACTIVE", "ADMINISTRATIVE_REQUEST", null, actorAccountId, deactivateCorrelationId);
    // The status transition and the persons/outbox commit are one
    // transaction: if history were written outside it, this row could
    // exist even though the guarded update below never happened.
    assertThat(countWhere("persons", "person_id = :id AND status = 'INACTIVE' AND version = 2", personId))
        .isEqualTo(1);
    assertThat(
            countWhere(
                "outbox_messages", "correlation_id = :id AND message_type = 'PersonDeactivated'", deactivateCorrelationId))
        .isEqualTo(1);

    UUID activateCorrelationId = UUID.randomUUID();
    CommandOutcome activateOutcome =
        dispatcher.dispatch(
            envelope(
                MessageTypes.ACTIVATE_PERSON,
                activateCorrelationId,
                actorAccountId,
                codec.toJsonNode(new ActivatePersonCommand(personId, 2))));

    assertThat(activateOutcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(((CommandOutcome.Succeeded) activateOutcome).aggregateVersion()).isEqualTo(3);
    // Append-only: the deactivation row from above must still be there.
    assertThat(countWhere("person_status_history", "person_id = :id", personId)).isEqualTo(2);
    assertHistoryRow(personId, "INACTIVE", "ACTIVE", null, null, actorAccountId, activateCorrelationId);
    assertThat(countWhere("persons", "person_id = :id AND status = 'ACTIVE' AND version = 3", personId))
        .isEqualTo(1);
  }

  private void assertHistoryRow(
      UUID personId,
      String expectedPrevious,
      String expectedNew,
      String expectedReasonCode,
      String expectedReasonDetail,
      UUID expectedActor,
      UUID expectedCorrelationId) {
    var row =
        jdbc.sql(
                """
                SELECT previous_status::text AS previous_status, new_status::text AS new_status,
                       reason_code, reason_detail,
                       changed_by_account_id, correlation_id, changed_at
                FROM person_status_history
                WHERE person_id = :personId AND correlation_id = :correlationId
                """)
            .param("personId", personId)
            .param("correlationId", expectedCorrelationId)
            .query((rs, rowNum) -> new Object[] {
              rs.getString("previous_status"),
              rs.getString("new_status"),
              rs.getString("reason_code"),
              rs.getString("reason_detail"),
              rs.getObject("changed_by_account_id", UUID.class),
              rs.getObject("correlation_id", UUID.class),
              rs.getTimestamp("changed_at")
            })
            .single();

    assertThat(row[0]).isEqualTo(expectedPrevious);
    assertThat(row[1]).isEqualTo(expectedNew);
    assertThat(row[2]).isEqualTo(expectedReasonCode);
    assertThat(row[3]).isEqualTo(expectedReasonDetail);
    assertThat(row[4]).isEqualTo(expectedActor);
    assertThat(row[5]).isEqualTo(expectedCorrelationId);
    assertThat(row[6]).isNotNull();
  }

  private long countWhere(String table, String whereClause, UUID personId) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + whereClause)
        .param("id", personId)
        .query(Long.class)
        .single();
  }

  private void createPerson(UUID personId, UUID actorAccountId) {
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
    CommandOutcome outcome =
        dispatcher.dispatch(
            envelope(MessageTypes.CREATE_PERSON, UUID.randomUUID(), actorAccountId, codec.toJsonNode(command)));
    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
  }

  private MessageEnvelope envelope(String messageType, UUID correlationId, UUID actorAccountId, JsonNode payload) {
    return new MessageEnvelope(
        UUID.randomUUID(),
        messageType,
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.now(),
        correlationId,
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        null,
        null,
        new Actor.Account(actorAccountId),
        payload);
  }
}
