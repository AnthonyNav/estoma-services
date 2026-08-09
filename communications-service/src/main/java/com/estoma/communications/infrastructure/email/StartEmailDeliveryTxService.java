package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.domain.email.EmailDelivery;
import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Starts the durable attempt immediately before the provider call. */
@Service
class StartEmailDeliveryTxService {
  private final EmailDeliveryRepository deliveries;
  private final EmailDeliveryAttemptRepository attempts;

  StartEmailDeliveryTxService(EmailDeliveryRepository deliveries, EmailDeliveryAttemptRepository attempts) {
    this.deliveries = deliveries;
    this.attempts = attempts;
  }

  @Transactional
  EmailDelivery start(EmailDelivery delivery) {
    Instant now = Instant.now();
    if (deliveries.startSending(delivery.emailDeliveryId(), delivery.claimToken(), now) != 1) {
      throw new LostEmailClaimException();
    }
    EmailDeliveryAttempt attempt = attempts.findByClaim(delivery.emailDeliveryId(), delivery.claimToken())
        .orElseThrow(LostEmailClaimException::new);
    if (attempts.update(attempt.callStarted(now)) != 1) {
      throw new LostEmailClaimException();
    }
    return deliveries.findById(delivery.emailDeliveryId()).orElseThrow(LostEmailClaimException::new);
  }
}
