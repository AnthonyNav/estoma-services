package com.estoma.communications.application.adapter;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.application.port.OutboxPort;
import com.estoma.communications.application.port.PasswordRecoveryDeliveryPort;
import com.estoma.communications.contracts.CommunicationStatus;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.event.CommunicationFailedPayload;
import com.estoma.communications.contracts.event.EmailDeliveryFailedPayload;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Converges WAITING_FOR_RECIPIENT recovery deliveries after Person/Account projections arrive in either order. */
@Component
public class PasswordRecoveryRecipientReconciler {

  private static final int BATCH_SIZE = 100;
  private final PasswordRecoveryDeliveryPort recoveries;
  private final CommunicationRepository communications;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;
  private final TransactionTemplate transactions;

  PasswordRecoveryRecipientReconciler(
      PasswordRecoveryDeliveryPort recoveries,
      CommunicationRepository communications,
      OutboxPort outbox,
      EnvelopeJsonCodec codec,
      PlatformTransactionManager transactionManager) {
    this.recoveries = recoveries;
    this.communications = communications;
    this.outbox = outbox;
    this.codec = codec;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  @Scheduled(fixedDelayString = "${estoma.communications.recovery-recipient-delay:1000}")
  public void reconcile() {
    for (PasswordRecoveryDeliveryPort.WaitingRecovery waiting : recoveries.waiting(BATCH_SIZE)) {
      transactions.executeWithoutResult(status -> reconcileOne(waiting, Instant.now()));
    }
  }

  private void reconcileOne(PasswordRecoveryDeliveryPort.WaitingRecovery waiting, Instant now) {
    if (!waiting.expiresAt().isAfter(now)) {
      if (recoveries.failWaiting(waiting.emailDeliveryId(), "RECOVERY_EXPIRED", now) == 1) {
        failCommunication(waiting, now, "RECOVERY_EXPIRED");
      }
      return;
    }
    if ("ACTIVE".equals(waiting.accountStatus())
        && "ACTIVE".equals(waiting.personStatus())
        && waiting.recipientAddress() != null
        && !waiting.recipientAddress().isBlank()) {
      recoveries.resolveRecipient(waiting.emailDeliveryId(), waiting.recipientAddress(), waiting.recipientName(), now);
    }
  }

  private void failCommunication(PasswordRecoveryDeliveryPort.WaitingRecovery waiting, Instant now, String code) {
    var communication = communications.findByIdForUpdate(waiting.communicationId()).orElseThrow();
    UUID correlationId = communication.correlationId() == null ? UUID.randomUUID() : communication.correlationId();
    if (communication.correlationId() == null) {
      communication = communication.withCorrelationId(correlationId);
      if (communications.update(communication) != 1) throw new IllegalStateException("recovery communication changed concurrently");
    }

    outbox.append(new OutboxAppendRequest(
        UUID.randomUUID(), CommunicationsEvent.EMAIL_DELIVERY_FAILED.wireName(), 1, MessageKind.EVENT.name(),
        communication.communicationId(), null, correlationId, communication.sourceMessageId(),
        codec.writeValueAsString(new EmailDeliveryFailedPayload(
            waiting.emailDeliveryId(), waiting.communicationId(), waiting.recipientAccountId(), 0, code, now)), now));

    if (communication.status() == CommunicationStatus.PROCESSING) {
      if (communications.update(communication.failed(now)) != 1) throw new IllegalStateException("recovery communication changed concurrently");
      outbox.append(new OutboxAppendRequest(
          UUID.randomUUID(), CommunicationsEvent.COMMUNICATION_FAILED.wireName(), 1, MessageKind.EVENT.name(),
          communication.communicationId(), null, correlationId, communication.sourceMessageId(),
          codec.writeValueAsString(new CommunicationFailedPayload(
              communication.communicationId(), communication.recipientAccountId(), communication.systemCode(),
              communication.communicationType(), List.of("EMAIL"), code, now)), now));
    }
  }
}
