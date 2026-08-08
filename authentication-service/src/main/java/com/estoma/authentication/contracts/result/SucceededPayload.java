package com.estoma.authentication.contracts.result;

import java.util.UUID;

/** {@code <Command>Succeeded} payload: the aggregate and its version after the change. Both may be null (RequestPasswordRecovery never reveals whether an account was found). */
public record SucceededPayload(UUID aggregateId, Integer version) {}
