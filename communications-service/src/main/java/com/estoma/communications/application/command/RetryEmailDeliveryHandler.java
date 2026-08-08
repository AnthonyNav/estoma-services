package com.estoma.communications.application.command;

import com.estoma.communications.application.EventToAppend;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.RetryEmailDeliveryCommand;
import com.estoma.communications.contracts.event.EmailDeliveryRetryScheduledPayload;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import org.springframework.stereotype.Component;

/** Handles {@code RetryEmailDelivery} (commands.md §3): forces a FAILED delivery back to PENDING, immediately claimable by the delivery worker. */
@Component
class RetryEmailDeliveryHandler implements CommandHandler {

  private final EmailDeliveryRepository repository;
  private final EnvelopeJsonCodec codec;

  RetryEmailDeliveryHandler(EmailDeliveryRepository repository, EnvelopeJsonCodec codec) {
    this.repository = repository;
    this.codec = codec;
  }

  @Override
  public String commandMessageType() {
    return CommunicationsCommand.RETRY_EMAIL_DELIVERY.wireName();
  }

  @Override
  public CommandHandlerResult handle(MessageEnvelope envelope) {
    RetryEmailDeliveryCommand command = codec.convertPayload(envelope, RetryEmailDeliveryCommand.class);
    EmailDelivery delivery =
        repository
            .findById(command.emailDeliveryId())
            .orElseThrow(() -> new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_FOUND, "email delivery " + command.emailDeliveryId() + " not found"));

    Instant now = Instant.now();
    EmailDelivery retried = delivery.retriedByOperator(now);
    repository.update(retried);

    return CommandHandlerResult.of(
        command.emailDeliveryId(), retried.attemptCount(),
        new EventToAppend(
            CommunicationsEvent.EMAIL_DELIVERY_RETRY_SCHEDULED.wireName(),
            new EmailDeliveryRetryScheduledPayload(command.emailDeliveryId(), delivery.communicationId(), delivery.attemptCount() + 1, null, now, now)));
  }
}
