package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.CommunicationRepository;
import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.EmailProviderPort;
import com.estoma.communications.application.port.OutboxAppendRequest;
import com.estoma.communications.application.port.OutboxPort;
import com.estoma.communications.contracts.CommunicationsEvent;
import com.estoma.communications.contracts.MessageKind;
import com.estoma.communications.contracts.event.CommunicationCompletedPayload;
import com.estoma.communications.contracts.event.CommunicationFailedPayload;
import com.estoma.communications.contracts.event.EmailDeliveryAcceptedPayload;
import com.estoma.communications.contracts.event.EmailDeliveryFailedPayload;
import com.estoma.communications.contracts.event.EmailDeliveryRetryScheduledPayload;
import com.estoma.communications.domain.communication.Communication;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import com.estoma.communications.infrastructure.messaging.EnvelopeJsonCodec;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implements "Email Delivery Operational Policy" (system_contract.md):
 * claims due deliveries in one transaction, calls the provider with NO
 * transaction open, then records the outcome in a second transaction --
 * data-consistency-architecture.md's hard rule that an external call must
 * never happen inside an open database transaction.
 *
 * <p>v1 backoff: up to 5 attempts total, gaps {@code 1m, 5m, 30m, 2h}
 * between them; the 5th failure is terminal (system_contract.md "Email
 * Delivery Operational Policy").
 */
@Component
public class EmailDeliveryWorker {

  private static final int MAX_ATTEMPTS = 5;
  private static final Duration[] RETRY_BACKOFF = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};

  private final EmailDeliveryRepository emailDeliveryRepository;
  private final EmailDeliveryAttemptRepository attemptRepository;
  private final CommunicationRepository communicationRepository;
  private final EmailProviderPort provider;
  private final OutboxPort outbox;
  private final EnvelopeJsonCodec codec;
  private final int batchSize;

  EmailDeliveryWorker(
      EmailDeliveryRepository emailDeliveryRepository,
      EmailDeliveryAttemptRepository attemptRepository,
      CommunicationRepository communicationRepository,
      EmailProviderPort provider,
      OutboxPort outbox,
      EnvelopeJsonCodec codec,
      @Value("${estoma.communications.email.worker.batch-size:20}") int batchSize) {
    this.emailDeliveryRepository = emailDeliveryRepository;
    this.attemptRepository = attemptRepository;
    this.communicationRepository = communicationRepository;
    this.provider = provider;
    this.outbox = outbox;
    this.codec = codec;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${estoma.communications.email.worker.poll-interval-ms:5000}")
  public void pollAndProcess() {
    for (EmailDelivery claimed : claimDue()) {
      // No transaction is open here: this is the only place the provider is called.
      EmailProviderPort.EmailSendResult result =
          provider.send(
              new EmailProviderPort.EmailSendRequest(
                  claimed.recipientAddress(), claimed.recipientName(), claimed.fromName(), claimed.fromAddress(), claimed.replyToAddress(),
                  claimed.contentMode(), claimed.subject(), claimed.textBody(), claimed.htmlBody(), claimed.templateCode(), claimed.templateData()));
      recordResult(claimed, result);
    }
  }

  @Transactional
  List<EmailDelivery> claimDue() {
    return emailDeliveryRepository.claimDue(batchSize);
  }

  @Transactional
  void recordResult(EmailDelivery claimed, EmailProviderPort.EmailSendResult result) {
    Instant now = Instant.now();
    if (result instanceof EmailProviderPort.EmailSendResult.Accepted accepted) {
      recordAccepted(claimed, accepted, now);
    } else if (result instanceof EmailProviderPort.EmailSendResult.Failed failed) {
      recordFailed(claimed, failed, now);
    }
  }

  private void recordAccepted(EmailDelivery claimed, EmailProviderPort.EmailSendResult.Accepted accepted, Instant now) {
    EmailDelivery updated = claimed.accepted(now);
    emailDeliveryRepository.update(updated);
    attemptRepository.insert(
        EmailDeliveryAttempt.accepted(
            UUID.randomUUID(), claimed.emailDeliveryId(), updated.attemptCount(), accepted.provider(), claimed.contentMode(), accepted.providerMessageId(),
            now, now));
    appendEvent(
        CommunicationsEvent.EMAIL_DELIVERY_ACCEPTED.wireName(),
        new EmailDeliveryAcceptedPayload(
            claimed.emailDeliveryId(), claimed.communicationId(), null, accepted.provider(), claimed.contentMode().name(), accepted.providerMessageId(),
            updated.attemptCount(), now),
        claimed.communicationId(), now);
    completeCommunication(claimed.communicationId(), now);
  }

  private void recordFailed(EmailDelivery claimed, EmailProviderPort.EmailSendResult.Failed failed, Instant now) {
    int attemptNumber = claimed.attemptCount() + 1;
    attemptRepository.insert(
        EmailDeliveryAttempt.failed(
            UUID.randomUUID(), claimed.emailDeliveryId(), attemptNumber, failed.provider(), claimed.contentMode(), failed.normalizedErrorCode(),
            failed.errorMessage(), now, now));

    if (attemptNumber >= MAX_ATTEMPTS) {
      EmailDelivery updated = claimed.failedTerminal(failed.normalizedErrorCode(), failed.errorMessage(), now);
      emailDeliveryRepository.update(updated);
      appendEvent(
          CommunicationsEvent.EMAIL_DELIVERY_FAILED.wireName(),
          new EmailDeliveryFailedPayload(claimed.emailDeliveryId(), claimed.communicationId(), null, attemptNumber, failed.normalizedErrorCode(), now),
          claimed.communicationId(), now);
      failCommunication(claimed.communicationId(), now);
    } else {
      Instant nextAttemptAt = now.plus(RETRY_BACKOFF[attemptNumber - 1]);
      EmailDelivery updated = claimed.retryScheduled(failed.normalizedErrorCode(), failed.errorMessage(), nextAttemptAt, now);
      emailDeliveryRepository.update(updated);
      appendEvent(
          CommunicationsEvent.EMAIL_DELIVERY_RETRY_SCHEDULED.wireName(),
          new EmailDeliveryRetryScheduledPayload(claimed.emailDeliveryId(), claimed.communicationId(), attemptNumber, failed.normalizedErrorCode(), nextAttemptAt, now),
          claimed.communicationId(), now);
    }
  }

  private void completeCommunication(UUID communicationId, Instant now) {
    Communication communication = communicationRepository.findById(communicationId).orElseThrow();
    communicationRepository.update(communication.completed(now));
    appendEvent(
        CommunicationsEvent.COMMUNICATION_COMPLETED.wireName(),
        // Simplification: this event does not currently distinguish whether an
        // inbox message was also part of the same communication -- see
        // pending_decisions.md.
        new CommunicationCompletedPayload(
            communicationId, communication.recipientAccountId(), communication.systemCode(), communication.communicationType(), List.of("EMAIL"), now),
        communicationId, now);
  }

  private void failCommunication(UUID communicationId, Instant now) {
    Communication communication = communicationRepository.findById(communicationId).orElseThrow();
    communicationRepository.update(communication.failed(now));
    appendEvent(
        CommunicationsEvent.COMMUNICATION_FAILED.wireName(),
        new CommunicationFailedPayload(communicationId, communication.recipientAccountId(), communication.systemCode(), communication.communicationType(), now),
        communicationId, now);
  }

  private void appendEvent(String messageType, Object payload, UUID aggregateId, Instant now) {
    outbox.append(
        new OutboxAppendRequest(
            UUID.randomUUID(), messageType, 1, MessageKind.EVENT.name(), aggregateId, null, UUID.randomUUID(), null, codec.writeValueAsString(payload), now));
  }
}
