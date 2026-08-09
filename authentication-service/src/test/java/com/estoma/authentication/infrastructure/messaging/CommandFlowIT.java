package com.estoma.authentication.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.application.port.AccountRepository;
import com.estoma.authentication.application.port.PersonStatusProjectionRepository;
import com.estoma.authentication.contracts.AuthenticationCommand;
import com.estoma.authentication.contracts.LoginType;
import com.estoma.authentication.contracts.MessageEnvelope;
import com.estoma.authentication.contracts.MessagingNames;
import com.estoma.authentication.contracts.command.CreateAccountCommand;
import com.estoma.authentication.infrastructure.messaging.outbound.OutboxRelay;
import com.estoma.authentication.testsupport.AbstractRabbitMqIT;
import com.estoma.authentication.testsupport.Envelopes;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * End-to-end proof that {@code CommandListener} and {@code OutboxRelay}
 * correctly wire the already-tested {@code CommandDispatcher} to real
 * RabbitMQ: a command published exactly as the BFF would publish it
 * reaches the dispatcher, and the resulting Outbox row reaches {@code
 * results.bff} once the relay runs.
 */
class CommandFlowIT extends AbstractRabbitMqIT {

  @Autowired private RabbitTemplate rabbitTemplate;
  @Autowired private AmqpAdmin amqpAdmin;
  @Autowired private EnvelopeJsonCodec codec;
  @Autowired private OutboxRelay outboxRelay;
  @Autowired private AccountRepository accountRepository;
  @Autowired private PersonStatusProjectionRepository personStatusProjectionRepository;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void clearOutbox() {
    jdbc.sql("DELETE FROM outbox_messages").update();
  }

  @Test
  void createAccountCommandFlowsThroughToAPublishedResult() throws InterruptedException {
    UUID personId = UUID.randomUUID();
    personStatusProjectionRepository.upsertIfNewer(personId, "ACTIVE", 1, Instant.now());
    UUID accountId = UUID.randomUUID();

    Queue resultsQueue = amqpAdmin.declareQueue();
    amqpAdmin.declareBinding(
        BindingBuilder.bind(resultsQueue)
            .to(new DirectExchange(MessagingNames.RESULTS_EXCHANGE))
            .with(AmqpNames.ROUTING_KEY_RESULTS_BFF));

    MessageEnvelope envelope =
        Envelopes.command(
            AuthenticationCommand.CREATE_ACCOUNT.wireName(),
            new CreateAccountCommand(accountId, personId, LoginType.MATRICULA, "cmd-flow-" + accountId));
    rabbitTemplate.send(MessagingNames.COMMANDS_EXCHANGE, AmqpNames.PRODUCER, new Message(codec.encode(envelope)));

    awaitAccountPersisted(accountId);
    outboxRelay.relay();

    MessageEnvelope result = awaitResult(resultsQueue, envelope.correlationId());
    assertThat(result.messageType()).isEqualTo("CreateAccountSucceeded");
    assertThat(result.correlationId()).isEqualTo(envelope.correlationId());
  }

  private void awaitAccountPersisted(UUID accountId) throws InterruptedException {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      if (accountRepository.findById(accountId).isPresent()) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("account " + accountId + " was not persisted within the deadline");
  }

  private MessageEnvelope awaitResult(Queue queue, UUID correlationId) {
    long deadline = System.currentTimeMillis() + 5_000;
    while (System.currentTimeMillis() < deadline) {
      Message received = rabbitTemplate.receive(queue.getName(), 100);
      if (received != null) {
        MessageEnvelope result = codec.decode(received.getBody());
        if (correlationId.equals(result.correlationId())) {
          return result;
        }
      }
    }
    throw new AssertionError("result for correlationId " + correlationId + " was not received within the deadline");
  }
}
