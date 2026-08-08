package com.estoma.communications.contracts.command;

import java.time.Instant;
import java.util.UUID;

/** {@code RegisterInboxVisit} (commands.md §3). {@code visitedAt} is a caller-trusted timestamp. */
public record RegisterInboxVisitCommand(UUID accountId, String systemCode, Instant visitedAt) {}
