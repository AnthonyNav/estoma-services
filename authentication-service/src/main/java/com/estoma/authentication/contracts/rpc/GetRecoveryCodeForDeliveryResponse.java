package com.estoma.authentication.contracts.rpc;

/** Transient response containing a code that must never be persisted or logged. */
public record GetRecoveryCodeForDeliveryResponse(String code) {}
