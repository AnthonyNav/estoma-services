package com.estoma.registros.contract;

import java.util.UUID;

/** ActorContext carries identity for audit; authorization is resolved upstream (BFF). */
public record Actor(String type, UUID accountId, String service) {}
