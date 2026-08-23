package com.estoma.platformbff.infrastructure.config;

public final class MessagingTopology {
  public static final String COMMANDS_EXCHANGE = "platform.commands";
  public static final String RESULTS_EXCHANGE = "platform.results";
  public static final String EVENTS_EXCHANGE = "platform.events";
  public static final String QUERIES_EXCHANGE = "platform.queries";
  public static final String SECURITY_RPC_EXCHANGE = "platform.security.rpc";
  public static final String RESULTS_QUEUE = "results.bff";
  public static final String PROJECTION_EVENTS_QUEUE = "events.bff-projections";
  public static final String AUTHENTICATION_RPC_QUEUE = "rpc.authentication";

  private MessagingTopology() {
  }
}
