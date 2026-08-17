package com.estoma.authorization.application.port;

import com.estoma.authorization.application.command.ApplicationResult;

/** Decodes durable idempotency state without coupling Application to a JSON library. */
public interface StoredApplicationResultDecoder {

  ApplicationResult decode(CommandIdempotencyRepository.IdempotencyRecord record);
}
