package com.estoma.communications.application.port;

import com.estoma.communications.domain.email.EmailDelivery;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EmailDeliveryRepository {

  Optional<EmailDelivery> findById(UUID emailDeliveryId);

  void insert(EmailDelivery delivery);

  int update(EmailDelivery delivery);

  /**
   * Atomically claims up to {@code batchSize} due {@code PENDING}/{@code
   * RETRY_SCHEDULED} rows (row-locked, skip-locked) and transitions them to
   * {@code SENDING} in the same statement, returning the claimed rows
   * already in {@code SENDING} state (system_contract.md "Email Delivery
   * Operational Policy").
   */
  List<EmailDelivery> claimDue(int batchSize);
}
