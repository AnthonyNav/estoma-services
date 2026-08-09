package com.estoma.communications.application.command;

import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.CommunicationAdminActionRepository;
import com.estoma.communications.contracts.CommunicationsCommand;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageEnvelope;
import com.estoma.communications.contracts.command.ResolveIndeterminateEmailDeliveryCommand;
import com.estoma.communications.application.EventToAppend;
import com.estoma.communications.contracts.event.EmailDeliveryIndeterminateResolvedPayload;
import com.estoma.communications.domain.CommunicationsRejectedException;
import com.estoma.communications.domain.RejectionCode;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import org.springframework.stereotype.Component;
import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.domain.communication.Communication;
import io.micrometer.core.instrument.MeterRegistry;

/** Administrative resolution entry point; SYSTEM actor authorization is enforced by the dispatcher policy. */
@Component
class ResolveIndeterminateEmailDeliveryHandler implements CommandHandler {
  private final EmailDeliveryRepository repository;
  private final EnvelopeJsonCodec codec;
  private final CommunicationAdminActionRepository actions;
  private final CommunicationRepository communications;
  private final MeterRegistry metrics;
  ResolveIndeterminateEmailDeliveryHandler(EmailDeliveryRepository repository, EnvelopeJsonCodec codec,
      CommunicationAdminActionRepository actions, CommunicationRepository communications, MeterRegistry metrics) {
    this.repository=repository; this.codec=codec; this.actions=actions; this.communications=communications; this.metrics=metrics;
  }
  @Override public String commandMessageType() { return CommunicationsCommand.RESOLVE_INDETERMINATE_EMAIL_DELIVERY.wireName(); }
  @Override public CommandHandlerResult handle(MessageEnvelope envelope) {
    ResolveIndeterminateEmailDeliveryCommand command=codec.convertPayload(envelope, ResolveIndeterminateEmailDeliveryCommand.class);
    var delivery=repository.findById(command.emailDeliveryId()).orElseThrow(() -> new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_FOUND, "email delivery not found"));
    if (delivery.status().name().equals("ACCEPTED") || delivery.status().name().equals("DELIVERED")) {
      throw new CommunicationsRejectedException(RejectionCode.EMAIL_ALREADY_ACCEPTED, "email delivery was already accepted");
    }
    if (!delivery.status().name().equals("INDETERMINATE")) {
      throw new CommunicationsRejectedException(RejectionCode.EMAIL_DELIVERY_NOT_INDETERMINATE, "email delivery is not indeterminate");
    }
    if (command.resolution() == ResolveIndeterminateEmailDeliveryCommand.Resolution.RESEND_AUTHORIZED && !command.riskAcknowledged())
      throw new CommunicationsRejectedException(RejectionCode.RESEND_DUPLICATE_RISK_NOT_ACKNOWLEDGED, "risk acknowledgment is required for resend");
    java.time.Instant now = java.time.Instant.now();
    actions.record(java.util.UUID.randomUUID(), envelope.messageId(), delivery.emailDeliveryId(), command.resolution().name(),
        envelope.actor().service(), command.reason() == null ? command.resolution().name() : command.reason(), "{}",
        command.resolution() == ResolveIndeterminateEmailDeliveryCommand.Resolution.RESEND_AUTHORIZED ? delivery.retryCycle() + 1 : delivery.retryCycle(),
        command.resolution() == ResolveIndeterminateEmailDeliveryCommand.Resolution.RESEND_AUTHORIZED, now);
    Communication communication = communications.findById(delivery.communicationId()).orElseThrow();
    switch (command.resolution()) {
      case RESEND_AUTHORIZED -> repository.update(delivery.resendAuthorized(now));
      case RECONCILED_ACCEPTED -> { repository.update(delivery.accepted(now)); if (communication.status() == com.estoma.communications.contracts.CommunicationStatus.PROCESSING) communications.update(communication.completed(now)); }
      case RECONCILED_FAILED -> { repository.update(delivery.failedTerminal("OPERATOR_RESOLVED_FAILED", "resolved by operator", now)); if (communication.status() == com.estoma.communications.contracts.CommunicationStatus.PROCESSING) communications.update(communication.failed(now)); }
    }
    metrics.counter("communications.email.indeterminate_resolved.total").increment();
    return CommandHandlerResult.of(command.emailDeliveryId(), delivery.attemptCount(), new EventToAppend(
        CommunicationsEvent.EMAIL_DELIVERY_INDETERMINATE_RESOLVED.wireName(),
        new EmailDeliveryIndeterminateResolvedPayload(delivery.emailDeliveryId(), delivery.communicationId(), command.resolution().name(), now)));
  }
}
