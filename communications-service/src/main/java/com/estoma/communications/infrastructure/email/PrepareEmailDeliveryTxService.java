package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import com.estoma.communications.domain.email.EmailDelivery;
import java.time.Instant;
import java.util.UUID;
import com.estoma.communications.application.port.EmailProviderPort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Validates the prepared item and fences the claimed row immediately before the provider call. */
@Service
class PrepareEmailDeliveryTxService {
  private final EmailDeliveryRepository repository;
  private final EmailDeliveryAttemptRepository attempts;
  PrepareEmailDeliveryTxService(EmailDeliveryRepository repository, EmailDeliveryAttemptRepository attempts) {
    this.repository = repository; this.attempts = attempts;
  }
  @Transactional public EmailDelivery prepare(EmailDelivery delivery) {
    return prepare(delivery, null);
  }

  @Transactional
  EmailDelivery prepare(EmailDelivery delivery, EmailProviderPort.EmailSendRequest rendered) {
    if (delivery.claimToken() == null || delivery.leaseUntil() == null || delivery.leaseUntil().isBefore(Instant.now()))
      throw new IllegalStateException("claim is missing or expired");
    if (rendered != null && delivery.contentMode() == com.estoma.communications.contracts.EmailContentMode.TEMPLATE
        && delivery.subject() == null && delivery.textBody() == null && delivery.htmlBody() == null) {
      EmailDelivery finalContent = EmailDelivery.withRenderedContent(delivery, rendered.subject(), rendered.textBody(), rendered.htmlBody());
      if (repository.persistRenderedContent(finalContent, delivery.claimToken()) != 1)
        throw new StartEmailDeliveryTxService.LostEmailClaimException();
      delivery = repository.findById(delivery.emailDeliveryId()).orElseThrow(StartEmailDeliveryTxService.LostEmailClaimException::new);
    }
    EmailDeliveryAttempt attempt = attempts.findReadyByDelivery(delivery.emailDeliveryId()).orElse(null);
    if (attempt == null) {
      Instant now = Instant.now();
      attempt = EmailDeliveryAttempt.readyToSend(UUID.randomUUID(), delivery.emailDeliveryId(),
          delivery.attemptCount() + 1, delivery.retryCycle(), delivery.cycleAttemptCount() + 1,
           delivery.claimToken(), "unknown", delivery.contentMode(), now);
      attempts.insert(attempt);
      attempt = attempts.findReadyByDelivery(delivery.emailDeliveryId()).orElseThrow(
          () -> new IllegalStateException("prepared attempt was not persisted"));
    } else if (!delivery.claimToken().equals(attempt.claimToken())
        && attempts.reassignClaim(attempt.emailDeliveryAttemptId(), attempt.claimToken(), delivery.claimToken()) != 1) {
      throw new IllegalStateException("claim lost while reassigning prepared attempt");
    }
    return delivery;
  }
}
