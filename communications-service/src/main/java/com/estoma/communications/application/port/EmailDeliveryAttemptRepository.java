package com.estoma.communications.application.port;

import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import java.util.UUID;

public interface EmailDeliveryAttemptRepository {

  void insert(EmailDeliveryAttempt attempt);
  int update(EmailDeliveryAttempt attempt);
  java.util.Optional<EmailDeliveryAttempt> findByClaim(UUID deliveryId, UUID claimToken);
  java.util.Optional<EmailDeliveryAttempt> findReadyByDelivery(UUID deliveryId);
  java.util.Optional<EmailDeliveryAttempt> findLastIndeterminate(UUID deliveryId);
  default java.util.Optional<EmailDeliveryAttempt> findLastIndeterminateForUpdate(UUID deliveryId) { return findLastIndeterminate(deliveryId); }
  /** Reconciles an unlocked indeterminate provider interaction without replacing provider evidence. */
  int reconcileIndeterminateWithoutClaim(EmailDeliveryAttempt attempt);
  int reassignClaim(UUID attemptId, UUID previousToken, UUID newToken);
  int resetUnstarted(UUID deliveryId, UUID claimToken);
}
