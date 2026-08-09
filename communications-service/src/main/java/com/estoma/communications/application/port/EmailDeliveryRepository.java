package com.estoma.communications.application.port;

import com.estoma.communications.domain.email.EmailDelivery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailDeliveryRepository {

  Optional<EmailDelivery> findById(UUID emailDeliveryId);

  void insert(EmailDelivery delivery);

  int update(EmailDelivery delivery);

  int persistRenderedContent(EmailDelivery delivery, UUID claimToken);

  /**
   * Atomically claims up to {@code batchSize} due {@code PENDING}/{@code
   * RETRY_SCHEDULED} rows (row-locked, skip-locked) and transitions them to
   * {@code SENDING} in the same statement, returning the claimed rows
   * already in {@code SENDING} state (system_contract.md "Email Delivery
   * Operational Policy").
   */
  List<EmailDelivery> claimDue(int batchSize);

  int releaseExpiredLeases();

  default List<EmailDelivery> claimDue(int batchSize, String workerId) { return claimDue(batchSize); }

  int startSending(UUID deliveryId, UUID claimToken, java.time.Instant now);

  int failPreProvider(UUID deliveryId, UUID claimToken, String code, String message, java.time.Instant now);

  default int updateClaimed(EmailDelivery delivery, UUID claimToken) { return update(delivery); }
}
