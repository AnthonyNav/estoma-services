package com.estoma.users.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Tunables for the Transactional Outbox relay and its housekeeping sweep.
 * See docs/system-contract.md §8, §10 (D8), §11.
 */
@ConfigurationProperties("estoma.outbox")
public record OutboxProperties(
    /** How many PENDING rows the relay claims per poll. */
    @DefaultValue("20") int claimBatchSize,
    /** How long a claimed (PROCESSING) row's lease lasts before the sweeper reclaims it. */
    @DefaultValue("30s") Duration lease,
    /** Retry attempts after which a still-PENDING row is marked FAILED. */
    @DefaultValue("10") int maxAttempts,
    /** How long a PUBLISHED row is kept before the sweeper purges it. */
    @DefaultValue("7d") Duration publishedRetention,
    /** How long a Consumer Inbox record is kept before the sweeper purges it (D8). */
    @DefaultValue("30d") Duration inboxRetention) {}
