package com.estoma.users.observability;

/** Consumer identity tags for metrics/logging — not all of these are Consumer Inbox keys. */
public final class ConsumerNames {

  private ConsumerNames() {}

  /** Matches {@code PersonCommandDispatcher}'s Inbox key for {@code commands.users-service}. */
  public static final String COMMANDS = "users-service.commands";

  /** {@code queries.users-service}'s consumer tag — no Consumer Inbox entry exists for it (side-effect-free, queries.md §1). */
  public static final String QUERIES = "users-service.queries";
}
