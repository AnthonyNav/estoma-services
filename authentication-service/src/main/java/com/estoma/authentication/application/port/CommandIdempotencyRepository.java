package com.estoma.authentication.application.port;

import java.util.UUID;
import com.estoma.authentication.contracts.result.ResultStatus;

/** Durable idempotency state for commands, keyed independently from the Consumer Inbox. */
public interface CommandIdempotencyRepository {

  ClaimResult claim(String producer, String operationType, String idempotencyKey, String requestFingerprint, UUID targetAccountId);

  void complete(String producer, String operationType, String idempotencyKey, ResultStatus resultStatus, String resultPayloadJson);

  sealed interface ClaimResult {
    record Claimed() implements ClaimResult {}

    record Replay(String resultPayloadJson) implements ClaimResult {}

    record Conflict() implements ClaimResult {}

    record InProgress() implements ClaimResult {}
  }
}
