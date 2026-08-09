package com.estoma.communications.application.command;

import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.CommunicationAdminActionRepository;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.ResolveIndeterminateEmailDeliveryCommand;
import com.estoma.communications.application.EventToAppend;
import com.estoma.communications.contracts.event.EmailDeliveryAcceptedPayload;
import com.estoma.communications.contracts.event.EmailDeliveryFailedPayload;
import com.estoma.communications.contracts.event.CommunicationCompletedPayload;
import com.estoma.communications.contracts.event.CommunicationFailedPayload;
import com.estoma.communications.contracts.event.EmailDeliveryRetryScheduledPayload;
import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import org.springframework.stereotype.Component;
import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.List;

/** Administrative resolution entry point; SYSTEM actor authorization is enforced by the dispatcher policy. */
@Component
class ResolveIndeterminateEmailDeliveryHandler implements CommandHandler {
  private final EmailDeliveryRepository repository;
  private final EnvelopeJsonCodec codec;
  private final CommunicationAdminActionRepository actions;
  private final CommunicationRepository communications;
  private final MeterRegistry metrics;
  private final EmailDeliveryAttemptRepository attempts;
  ResolveIndeterminateEmailDeliveryHandler(EmailDeliveryRepository repository, EnvelopeJsonCodec codec,
      CommunicationAdminActionRepository actions, CommunicationRepository communications, MeterRegistry metrics, EmailDeliveryAttemptRepository attempts) {
    this.repository=repository; this.codec=codec; this.actions=actions; this.communications=communications; this.metrics=metrics; this.attempts=attempts;
  }
  @Override public String commandMessageType() { return CommunicationsCommand.RESOLVE_INDETERMINATE_EMAIL_DELIVERY.wireName(); }
  @Override public CommandHandlerResult handle(MessageEnvelope envelope) {
    ResolveIndeterminateEmailDeliveryCommand command=codec.convertPayload(envelope, ResolveIndeterminateEmailDeliveryCommand.class);
    var delivery=repository.findByIdForUpdate(command.emailDeliveryId()).orElseThrow(() -> new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_FOUND, "email delivery not found"));
    if (delivery.status().name().equals("ACCEPTED") || delivery.status().name().equals("DELIVERED")) {
      throw new CommunicationsRejectedException(RejectionCode.EMAIL_ALREADY_ACCEPTED, "email delivery was already accepted");
    }
    if (!delivery.status().name().equals("INDETERMINATE")) {
      throw new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_INDETERMINATE, "email delivery is not indeterminate");
    }
    if (command.resolution() == ResolveIndeterminateEmailDeliveryCommand.Resolution.RESEND_AUTHORIZED && !command.riskAcknowledged())
      throw new CommunicationsRejectedException(RejectionCode.RESEND_DUPLICATE_RISK_NOT_ACKNOWLEDGED, "risk acknowledgment is required for resend");

    // The delivery lock is acquired above. Resolve's optional attempt lock must precede its parent lock.
    var lockedAttempt = command.resolution() == ResolveIndeterminateEmailDeliveryCommand.Resolution.RESEND_AUTHORIZED
        ? java.util.Optional.<EmailDeliveryAttempt>empty()
        : attempts.findLastIndeterminateForUpdate(delivery.emailDeliveryId());
    Communication communication = communications.findByIdForUpdate(delivery.communicationId()).orElseThrow();
    java.time.Instant now = java.time.Instant.now();
    List<EventToAppend> events = new ArrayList<>();
    EmailDelivery updatedDelivery;
    EmailDeliveryAttempt updatedAttempt = null;
    Communication updatedCommunication = communication.withCorrelationId(envelope.correlationId());
    boolean communicationChanged = updatedCommunication != communication;
    boolean emailIsRequired = communication.requestedChannels().isEmpty() || communication.requestedChannels().contains("EMAIL");

    switch (command.resolution()) {
      case RESEND_AUTHORIZED -> {
        updatedDelivery = delivery.resendAuthorized(now);
        events.add(new EventToAppend(CommunicationsEvent.EMAIL_DELIVERY_RETRY_SCHEDULED.wireName(),
            new EmailDeliveryRetryScheduledPayload(delivery.emailDeliveryId(), delivery.communicationId(), updatedDelivery.attemptCount(), delivery.lastErrorCode(), now, now)));
      }
      case RECONCILED_ACCEPTED -> {
        var attempt = lockedAttempt.orElseThrow(
            () -> new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_INDETERMINATE,
                "accepted reconciliation requires a real indeterminate attempt"));
        updatedDelivery = delivery.accepted(now);
        updatedAttempt = attempt.reconciledAccepted(now);
        events.add(new EventToAppend(CommunicationsEvent.EMAIL_DELIVERY_ACCEPTED.wireName(), new EmailDeliveryAcceptedPayload(delivery.emailDeliveryId(), delivery.communicationId(), communication.recipientAccountId(), attempt.provider(), delivery.contentMode().name(), attempt.providerMessageId(), attempt.attemptNumber(), now)));
        if (communication.status() == CommunicationStatus.PROCESSING && emailIsRequired) {
          updatedCommunication = updatedCommunication.completed(now);
          communicationChanged = true;
          events.add(new EventToAppend(CommunicationsEvent.COMMUNICATION_COMPLETED.wireName(), new CommunicationCompletedPayload(communication.communicationId(), communication.recipientAccountId(), communication.systemCode(), communication.communicationType(), communication.requestedChannels(), now)));
        }
      }
      case RECONCILED_FAILED -> {
        updatedDelivery = delivery.failedTerminal("OPERATOR_RESOLVED_FAILED", "resolved by operator", now);
        updatedAttempt = lockedAttempt.map(attempt -> attempt.reconciledFailed("OPERATOR_RESOLVED_FAILED", "resolved by operator", now)).orElse(null);
        if (communication.status() == CommunicationStatus.PROCESSING && emailIsRequired) {
          updatedCommunication = updatedCommunication.failed(now);
          communicationChanged = true;
          events.add(new EventToAppend(CommunicationsEvent.COMMUNICATION_FAILED.wireName(), new CommunicationFailedPayload(communication.communicationId(), communication.recipientAccountId(), communication.systemCode(), communication.communicationType(), List.of("EMAIL"), "OPERATOR_RESOLVED_FAILED", now)));
        }
        events.add(new EventToAppend(CommunicationsEvent.EMAIL_DELIVERY_FAILED.wireName(), new EmailDeliveryFailedPayload(delivery.emailDeliveryId(), delivery.communicationId(), communication.recipientAccountId(), delivery.attemptCount(), "OPERATOR_RESOLVED_FAILED", now)));
      }
      default -> throw new IllegalStateException("unsupported indeterminate delivery resolution");
    }

    if (updatedAttempt != null && attempts.reconcileIndeterminateWithoutClaim(updatedAttempt) != 1)
      throw new IllegalStateException("email delivery attempt update lost after resolution validation");
    if (repository.update(updatedDelivery) != 1) throw new IllegalStateException("email delivery update lost after resolution validation");
    if (communicationChanged && communications.update(updatedCommunication) != 1)
      throw new IllegalStateException("communication update lost after resolution validation");
    actions.record(java.util.UUID.randomUUID(), envelope.messageId(), delivery.emailDeliveryId(), command.resolution().name(),
        codec.writeValueAsString(envelope.actor()), command.reason() == null ? command.resolution().name() : command.reason(), "{}",
        updatedDelivery.retryCycle(), command.resolution() == ResolveIndeterminateEmailDeliveryCommand.Resolution.RESEND_AUTHORIZED, now);
    metrics.counter("communications.email.indeterminate_resolved.total").increment();
    return CommandHandlerResult.of(command.emailDeliveryId(), delivery.attemptCount(), events.toArray(EventToAppend[]::new));
  }
}
