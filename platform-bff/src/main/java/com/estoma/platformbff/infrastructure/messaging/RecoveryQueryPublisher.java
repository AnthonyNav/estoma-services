package com.estoma.platformbff.infrastructure.messaging;

import com.estoma.platformbff.domain.projection.RecoveryQuery;

public interface RecoveryQueryPublisher {
  void publish(RecoveryQuery query);
}
