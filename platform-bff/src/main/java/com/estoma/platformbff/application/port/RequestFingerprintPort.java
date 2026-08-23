package com.estoma.platformbff.application.port;

import com.estoma.platformbff.contracts.messaging.ActorContext;
import java.util.UUID;

public interface RequestFingerprintPort {
  NormalizedRequest normalize(
      String publicOperation,
      String messageType,
      String payloadJson,
      UUID aggregateId,
      Integer aggregateVersion,
      ActorContext actor);

  record NormalizedRequest(String canonicalPayloadJson, String fingerprint) {
  }
}
