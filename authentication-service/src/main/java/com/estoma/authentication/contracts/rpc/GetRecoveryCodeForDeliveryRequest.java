package com.estoma.authentication.contracts.rpc;

import java.util.UUID;

/** Internal Security RPC request accepted exclusively from communications-service. */
public record GetRecoveryCodeForDeliveryRequest(UUID recoveryId) {}
