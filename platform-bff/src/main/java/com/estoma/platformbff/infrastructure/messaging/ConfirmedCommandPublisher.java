package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.domain.messaging.OutboxMessage;

public interface ConfirmedCommandPublisher {
  void publish(OutboxMessage message);
}
