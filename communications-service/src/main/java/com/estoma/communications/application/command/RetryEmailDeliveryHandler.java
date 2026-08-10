package com.estoma.communications.application.command;

import com.estoma.communications.application.EventToAppend;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.CommunicationAdminActionRepository;
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
import java.util.UUID;
import org.springframework.stereotype.Component;
import com.estoma.communications.application.port.CommunicationRepository;
import io.micrometer.core.instrument.MeterRegistry;

/** Handles {@code RetryEmailDelivery} (commands.md §3): forces a FAILED delivery back to PENDING, immediately claimable by the delivery worker. */
@Component
class RetryEmailDeliveryHandler implements CommandHandler {

  private final EmailDeliveryRepository repository;
  private final EnvelopeJsonCodec codec;
  private final CommunicationAdminActionRepository actions;
  private final CommunicationRepository communications;
  private final MeterRegistry metrics;

  RetryEmailDeliveryHandler(EmailDeliveryRepository repository, EnvelopeJsonCodec codec, CommunicationAdminActionRepository actions,
      CommunicationRepository communications, MeterRegistry metrics) {
    this.repository = repository;
    this.codec = codec;
    this.actions = actions;
    this.communications = communications;
    this.metrics = metrics;
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
            .findByIdForUpdate(command.emailDeliveryId())
            .orElseThrow(() -> new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_FOUND, "email delivery " + command.emailDeliveryId() + " not found"));

    Instant now = Instant.now();
    // Lock the parent before calculating either transition; delivery is always locked first.
    var communication = communications.findByIdForUpdate(delivery.communicationId()).orElseThrow();
    EmailDelivery retried = delivery.retriedByOperator(now);
    var reopened = communication.withCorrelationId(envelope.correlationId()).reopenedForRetry(now);
    var event = new EventToAppend(
        CommunicationsEvent.EMAIL_DELIVERY_RETRY_SCHEDULED.wireName(),
        new EmailDeliveryRetryScheduledPayload(command.emailDeliveryId(), delivery.communicationId(), retried.attemptCount(), null, now, now));

    if (repository.update(retried) != 1) throw new IllegalStateException("email delivery update lost after retry validation");
    if (communications.update(reopened) != 1) throw new IllegalStateException("communication update lost after retry validation");
    actions.record(UUID.randomUUID(), envelope.messageId(), delivery.emailDeliveryId(), "RETRY_EMAIL_DELIVERY",
        codec.writeValueAsString(envelope.actor()), command.reason() == null ? "manual retry" : command.reason(), "{}", retried.retryCycle(), false, now);
    metrics.counter("communications.email.manual_retry.total").increment();

    return CommandHandlerResult.of(
        command.emailDeliveryId(), retried.attemptCount(),
        event);
  }
}
