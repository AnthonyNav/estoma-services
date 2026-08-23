package com.estoma.authorization.infrastructure.messaging.outbound;

import java.time.Duration;

interface OutboxBackoffPolicy {

  Duration delayForAttempt(int attempt);
}
