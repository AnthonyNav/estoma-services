package com.estoma.communications.infrastructure.persistence;

import com.estoma.communications.application.CommandDispatcher;
import com.estoma.communications.contracts.Actor;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.command.ExpireInboxMessagesCommand;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Triggers {@code ExpireInboxMessages} in-process (execution_plan.md Phase
 * 2 step 11) -- same pattern as {@code AppointmentCancelledAdapter} and
 * {@link com.estoma.communications.infrastructure.email.EmailDeliveryWorker}:
 * this is an internal scheduled process, not a RabbitMQ self-command
 * (system_contract.md "Internal Architecture").
 */
@Component
class InboxExpirationScheduler {

  private final CommandDispatcher dispatcher;
  private final EnvelopeJsonCodec codec;
  private final int batchSize;

  InboxExpirationScheduler(
      CommandDispatcher dispatcher, EnvelopeJsonCodec codec, @Value("${estoma.communications.inbox.expiration.batch-size:100}") int batchSize) {
    this.dispatcher = dispatcher;
    this.codec = codec;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${estoma.communications.inbox.expiration.poll-interval-ms:300000}")
  public void expireDueMessages() {
    Instant now = Instant.now();
    MessageEnvelope envelope =
        new MessageEnvelope(
            UUID.randomUUID(), CommunicationsCommand.EXPIRE_INBOX_MESSAGES.wireName(), 1, MessageKind.COMMAND, "communications", now, UUID.randomUUID(),
            null, UUID.randomUUID(), null, null, null, Actor.system("communications"),
            codec.toJsonNode(new ExpireInboxMessagesCommand(now, batchSize)));
    dispatcher.dispatchInternal(envelope);
  }
}
