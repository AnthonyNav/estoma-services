package com.estoma.communications.application.port;

import com.estoma.communications.domain.email.EmailDeliveryAttempt;
import java.util.UUID;

public interface EmailDeliveryAttemptRepository {

  void insert(EmailDeliveryAttempt attempt);
  int update(EmailDeliveryAttempt attempt);
  java.util.Optional<EmailDeliveryAttempt> findByClaim(UUID deliveryId, UUID claimToken);
  java.util.Optional<EmailDeliveryAttempt> findReadyByDelivery(UUID deliveryId);
  int reassignClaim(UUID attemptId, UUID previousToken, UUID newToken);
}
