package com.estoma.washadministration.application.port;

import java.time.Instant;
import java.util.UUID;

public interface ProcessedMessageRepository {

  boolean registerIfAbsent(String consumerName, UUID messageId, UUID correlationId, Instant processedAt);
}
