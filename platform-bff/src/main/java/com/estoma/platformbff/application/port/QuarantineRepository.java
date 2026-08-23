package com.estoma.platformbff.application.port;

import java.time.Instant;
import java.util.UUID;

public interface QuarantineRepository {
  void insert(String consumerName, UUID messageId, String reasonCode, String bodySha256, Instant receivedAt);
}
