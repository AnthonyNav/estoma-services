package com.estoma.authorization.contracts;

import java.util.UUID;

/** Closed by the V1 schema as ACCOUNT {accountId} or SYSTEM {service}. */
public record WireActor(String type, UUID accountId, String service) {}
