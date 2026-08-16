package com.estoma.washadministration.contracts;

public final class MessagingNames {

  public static final String COMMANDS_EXCHANGE = "platform.commands";
  public static final String QUERIES_EXCHANGE = "platform.queries";
  public static final String RESULTS_EXCHANGE = "platform.results";
  public static final String EVENTS_EXCHANGE = "platform.events";
  public static final String DLX_EXCHANGE = "platform.dlx";

  public static final String LOGICAL_COMPONENT_ID = "wash-administration";
  public static final String COMMAND_QUEUE = "commands.wash-administration";
  public static final String QUERY_QUEUE = "queries.wash-administration";
  public static final String EVENTS_QUEUE = "events.wash-administration";
  public static final String RESULTS_QUEUE = "results.wash-administration";

  public static final String COMMAND_ROUTING_KEY = LOGICAL_COMPONENT_ID;
  public static final String QUERY_ROUTING_KEY = LOGICAL_COMPONENT_ID;
  public static final String RESULTS_BFF_ROUTING_KEY = "bff";

  public static final String COMMAND_RETRY_1 = COMMAND_QUEUE + ".retry.1";
  public static final String COMMAND_RETRY_2 = COMMAND_QUEUE + ".retry.2";
  public static final String COMMAND_DLQ = COMMAND_QUEUE + ".dlq";
  public static final String QUERY_RETRY_1 = QUERY_QUEUE + ".retry.1";
  public static final String QUERY_RETRY_2 = QUERY_QUEUE + ".retry.2";
  public static final String QUERY_DLQ = QUERY_QUEUE + ".dlq";
  public static final String EVENTS_RETRY_1 = EVENTS_QUEUE + ".retry.1";
  public static final String EVENTS_RETRY_2 = EVENTS_QUEUE + ".retry.2";
  public static final String EVENTS_DLQ = EVENTS_QUEUE + ".dlq";
  public static final String RESULTS_RETRY_1 = RESULTS_QUEUE + ".retry.1";
  public static final String RESULTS_RETRY_2 = RESULTS_QUEUE + ".retry.2";
  public static final String RESULTS_DLQ = RESULTS_QUEUE + ".dlq";

  public static final String AUTHORIZATION_QUERY_QUEUE = "queries.authorization";
  public static final String AUTHORIZATION_QUERY_ROUTING_KEY = "authorization";

  private MessagingNames() {}
}
