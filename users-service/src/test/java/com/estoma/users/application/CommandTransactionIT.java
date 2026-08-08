package com.estoma.users.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.application.command.CreatePersonCommand;
import com.estoma.users.contract.Actor;
import com.estoma.users.contract.MessageEnvelope;
import com.estoma.users.contract.MessageKind;
import com.estoma.users.contract.MessageTypes;
import com.estoma.users.messaging.EnvelopeJsonCodec;
import com.estoma.users.testsupport.AbstractPostgresIT;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Verifies the transaction boundary directly against the dispatcher, with
 * no AMQP involved: one commit must produce exactly {@code {persons row,
 * processed_messages row, 2 outbox_messages rows (RESULT + EVENT)}}
 * (docs/system-contract.md §7).
 */
class CommandTransactionIT extends AbstractPostgresIT {

  @Autowired private PersonCommandDispatcher dispatcher;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private JdbcClient jdbc;

  @Test
  void oneSuccessfulCommandCommitsExactlyFourRowsAcrossThreeTables() {
    UUID personId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    MessageEnvelope envelope = createPersonEnvelope(personId, correlationId);

    CommandOutcome outcome = dispatcher.dispatch(envelope);

    assertThat(outcome).isInstanceOf(CommandOutcome.Succeeded.class);
    assertThat(((CommandOutcome.Succeeded) outcome).aggregateVersion()).isEqualTo(1);

    assertThat(countWhere("persons", "person_id = :id", "id", personId)).isEqualTo(1);
    assertThat(countWhere("processed_messages", "message_id = :id", "id", envelope.messageId())).isEqualTo(1);
    assertThat(countWhere("outbox_messages", "correlation_id = :id", "id", correlationId)).isEqualTo(2);

    long resultRows =
        jdbc.sql("SELECT count(*) FROM outbox_messages WHERE correlation_id = :id AND message_kind = 'RESULT'")
            .param("id", correlationId)
            .query(Long.class)
            .single();
    long eventRows =
        jdbc.sql("SELECT count(*) FROM outbox_messages WHERE correlation_id = :id AND message_kind = 'EVENT'")
            .param("id", correlationId)
            .query(Long.class)
            .single();
    assertThat(resultRows).isEqualTo(1);
    assertThat(eventRows).isEqualTo(1);
  }

  @Test
  void aDuplicateDeliveryOfTheSameMessageIdIsANoOp() {
    UUID personId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    MessageEnvelope envelope = createPersonEnvelope(personId, correlationId);

    dispatcher.dispatch(envelope);
    CommandOutcome secondOutcome = dispatcher.dispatch(envelope); // same messageId

    assertThat(secondOutcome).isInstanceOf(CommandOutcome.Duplicate.class);
    assertThat(countWhere("persons", "person_id = :id", "id", personId)).isEqualTo(1);
    assertThat(countWhere("outbox_messages", "correlation_id = :id", "id", correlationId)).isEqualTo(2);
  }

  private long countWhere(String table, String whereClause, String paramName, Object paramValue) {
    return jdbc.sql("SELECT count(*) FROM " + table + " WHERE " + whereClause)
        .param(paramName, paramValue)
        .query(Long.class)
        .single();
  }

  private MessageEnvelope createPersonEnvelope(UUID personId, UUID correlationId) {
    CreatePersonCommand command =
        new CreatePersonCommand(personId, "Ada", "Lovelace", null, "ada." + personId + "@example.edu", null);
    return new MessageEnvelope(
        UUID.randomUUID(),
        MessageTypes.CREATE_PERSON,
        1,
        MessageKind.COMMAND,
        "bff",
        Instant.now(),
        correlationId,
        null,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        personId,
        null,
        new Actor.Account(UUID.randomUUID()),
        codec.toJsonNode(command));
  }
}
