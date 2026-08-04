package com.estoma.authentication.contracts;

public final class MessagingNames {

  public static final String COMMANDS_EXCHANGE = "platform.commands";
  public static final String RESULTS_EXCHANGE = "platform.results";
  public static final String EVENTS_EXCHANGE = "platform.events";
  public static final String SECURITY_RPC_EXCHANGE = "platform.security.rpc";

  public static final String AUTHENTICATION_COMMAND_QUEUE = "commands.authentication-service";
  public static final String AUTHENTICATION_RPC_QUEUE = "rpc.authentication";
  public static final String BFF_RESULTS_QUEUE = "results.bff";

  private MessagingNames() {}
}
