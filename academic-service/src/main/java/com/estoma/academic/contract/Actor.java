package com.estoma.academic.contract;

import java.util.UUID;

/** ActorContext carries identity for audit; authorization is resolved from local projections. */
public record Actor(String type, UUID accountId, String service) {}
