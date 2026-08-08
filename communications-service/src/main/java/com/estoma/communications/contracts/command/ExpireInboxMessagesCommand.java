package com.estoma.communications.contracts.command;

import java.time.Instant;

/** {@code ExpireInboxMessages} (commands.md §3): expires ACTIVE inbox messages whose expiresAt is at or before cutoff. */
public record ExpireInboxMessagesCommand(Instant cutoff, int batchSize) {}
