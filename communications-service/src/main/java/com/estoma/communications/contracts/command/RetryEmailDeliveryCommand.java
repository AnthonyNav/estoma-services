package com.estoma.communications.contracts.command;

import java.util.UUID;

/** {@code RetryEmailDelivery} (commands.md §3). The actor is carried by the envelope, not this payload. */
public record RetryEmailDeliveryCommand(UUID emailDeliveryId, String reason) {}
