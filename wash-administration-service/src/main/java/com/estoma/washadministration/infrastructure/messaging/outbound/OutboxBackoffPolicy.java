package com.estoma.washadministration.infrastructure.messaging.outbound;

import java.time.Duration;

public interface OutboxBackoffPolicy {

  Duration delayForAttempt(int attempt);
}
