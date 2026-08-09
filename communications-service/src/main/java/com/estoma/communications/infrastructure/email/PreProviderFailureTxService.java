package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.application.port.OutboxPort;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.event.CommunicationFailedPayload;
import com.estoma.communications.contracts.event.EmailDeliveryFailedPayload;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Records deterministic validation failures without creating a provider attempt. */
@Service
class PreProviderFailureTxService {
  private final EmailDeliveryRepository deliveries;
  private final CommunicationRepository communications;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;

  PreProviderFailureTxService(EmailDeliveryRepository deliveries, CommunicationRepository communications,
      OutboxPort outbox, EnvelopeJsonCodec codec) {
    this.deliveries = deliveries;
    this.communications = communications;
    this.outbox = outbox;
    this.codec = codec;
  }

  @Transactional
  void record(com.estoma.communications.domain.email.EmailDelivery delivery, String code, String message) {
    Instant now = Instant.now();
    if (deliveries.failPreProvider(delivery.emailDeliveryId(), delivery.claimToken(), code, message, now) != 1) return;
    Communication communication = communications.findById(delivery.communicationId()).orElseThrow();
    if (communication.correlationId() == null) {
      communication = communication.withCorrelationId(UUID.randomUUID());
      if (communications.update(communication) != 1) throw new IllegalStateException("communication claim lost while tracing failure");
    }
    outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.EMAIL_DELIVERY_FAILED.wireName(), 1,
        MessageKind.EVENT.name(), communication.communicationId(), null, communication.correlationId(), null,
        codec.writeValueAsString(new EmailDeliveryFailedPayload(delivery.emailDeliveryId(), delivery.communicationId(), communication.recipientAccountId(), delivery.attemptCount(), code, now)), now));
    if (communication.status() == com.estoma.communications.contracts.CommunicationStatus.PROCESSING) {
      if (communications.update(communication.failed(now)) != 1) throw new IllegalStateException("communication claim lost while failing");
      outbox.append(new OutboxAppendRequest(UUID.randomUUID(), CommunicationsEvent.COMMUNICATION_FAILED.wireName(), 1,
          MessageKind.EVENT.name(), communication.communicationId(), null, communication.correlationId(), null,
          codec.writeValueAsString(new CommunicationFailedPayload(communication.communicationId(), communication.recipientAccountId(),
               communication.systemCode(), communication.communicationType(), java.util.List.of("EMAIL"), code, now)), now));
    }
  }
}
