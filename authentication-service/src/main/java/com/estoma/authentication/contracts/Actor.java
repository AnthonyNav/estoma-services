package com.estoma.authentication.contracts;

import java.util.UUID;

/**
 * The actor an envelope carries responsibility for. Authorization checks
 * must use this -- never data taken from the payload
 * (security-observability.md:156).
 */
public record Actor(UUID accountId) {}
