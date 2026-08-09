package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailDeliveryAttemptRepository;
import com.estoma.communications.application.port.EmailDeliveryRepository;
import com.estoma.communications.contracts.EmailAttemptStatus;
import com.estoma.communications.contracts.EmailDeliveryStatus;
import com.estoma.communications.domain.email.EmailDelivery;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Restores a claimed delivery when the provider executor rejected before any call started. */
@Service
class ProviderNotStartedRecoveryTxService {
  private final EmailDeliveryRepository deliveries;
  private final EmailDeliveryAttemptRepository attempts;

  ProviderNotStartedRecoveryTxService(EmailDeliveryRepository deliveries, EmailDeliveryAttemptRepository attempts) {
    this.deliveries = deliveries;
    this.attempts = attempts;
  }

  @Transactional
  void recover(EmailDelivery claimed) {
    if (claimed.claimToken() == null) throw new LostEmailClaimException();
    Instant now = Instant.now();
    EmailDelivery delivery = deliveries.findByIdForUpdate(claimed.emailDeliveryId()).orElseThrow(LostEmailClaimException::new);
    if (delivery.status() != EmailDeliveryStatus.SENDING || !claimed.claimToken().equals(delivery.claimToken())
        || delivery.leaseUntil() == null || !delivery.leaseUntil().isAfter(now)
        || delivery.attemptCount() <= 0 || delivery.cycleAttemptCount() <= 0) {
      throw new LostEmailClaimException();
    }
    var attempt = attempts.findByClaimForUpdate(delivery.emailDeliveryId(), claimed.claimToken())
        .orElseThrow(LostEmailClaimException::new);
    if (attempt.status() != EmailAttemptStatus.CALL_STARTED) throw new LostEmailClaimException();

    if (deliveries.requeueUnstarted(delivery.emailDeliveryId(), claimed.claimToken(), now) != 1) {
      throw new LostEmailClaimException();
    }
    if (attempts.resetUnstarted(delivery.emailDeliveryId(), claimed.claimToken()) != 1) {
      throw new LostEmailClaimException();
    }
  }
}
