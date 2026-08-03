package com.estoma.users.observability;

/** The Consumer Inbox {@code consumer_name} this service registers under. */
public final class ConsumerNames {

  private ConsumerNames() {}

  /** Matches {@code PersonCommandDispatcher}'s Inbox key for {@code commands.users-service}. */
  public static final String COMMANDS = "users-service.commands";
}
