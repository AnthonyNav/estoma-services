package com.estoma.platformbff.application.security;

import com.estoma.platformbff.contracts.messaging.ActorContext;
import java.util.UUID;

public interface SecurityRpcPort {
  RpcCallResult call(
      String operation,
      ActorContext actor,
      UUID targetAccountId,
      String idempotencyKey,
      String payloadJson);

  record RpcCallResult(String status, String dataJson, String errorCode) {
  }
}
