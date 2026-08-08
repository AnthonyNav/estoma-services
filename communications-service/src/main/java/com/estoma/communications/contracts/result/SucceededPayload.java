package com.estoma.communications.contracts.result;

import java.util.UUID;

/** Generic durable-command success result payload, same shape as authentication-service's. */
public record SucceededPayload(UUID aggregateId, Integer aggregateVersion) {}
