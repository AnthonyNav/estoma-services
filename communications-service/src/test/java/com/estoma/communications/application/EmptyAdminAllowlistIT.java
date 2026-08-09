package com.estoma.communications.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.command.RetryEmailDeliveryCommand;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.testsupport.AbstractPostgresIT;
import com.estoma.communications.testsupport.Envelopes;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "estoma.communications.admin.system-allowlist=")
class EmptyAdminAllowlistIT extends AbstractPostgresIT {
  @Autowired private CommandDispatcher dispatcher;
  @Autowired private JdbcClient jdbc;

  @Test
  void emptyAllowlistDurablyRejectsAdministrativeCommands() {
    var envelope = Envelopes.command(CommunicationsCommand.RETRY_EMAIL_DELIVERY.wireName(),
        new RetryEmailDeliveryCommand(UUID.randomUUID(), "operator retry"));

    var outcome = dispatcher.dispatch(envelope);

    assertThat(outcome).isEqualTo(new CommandOutcome.Rejected(RejectionCode.ACTOR_NOT_AUTHORIZED));
    assertThat(jdbc.sql("SELECT count(*) FROM processed_messages WHERE consumer_name = 'communications-service.commands' AND message_id = :id")
        .param("id", envelope.messageId()).query(Long.class).single()).isEqualTo(1);
    assertThat(jdbc.sql("SELECT payload::text FROM outbox_messages WHERE causation_id = :id AND message_kind = 'RESULT'")
        .param("id", envelope.messageId()).query(String.class).single()).contains("ACTOR_NOT_AUTHORIZED");
  }
}
