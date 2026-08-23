package com.estoma.authorization.contracts;

/** Canonical logical names for Authorization V1 RabbitMQ topology. */
public final class MessagingNames {

  public static final String LOGICAL_COMPONENT_ID = "authorization";
  public static final String COMMANDS_EXCHANGE = "platform.commands";
  public static final String QUERIES_EXCHANGE = "platform.queries";
  public static final String RESULTS_EXCHANGE = "platform.results";
  public static final String EVENTS_EXCHANGE = "platform.events";
  public static final String DLX_EXCHANGE = "platform.dlx";

  public static final String COMMAND_QUEUE = "commands.authorization";
  public static final String QUERY_QUEUE = "queries.authorization";
  public static final String EVENT_QUEUE = "events.authorization";
  public static final String RESULT_QUEUE = "results.authorization";

  public static final String COMMAND_RETRY_1 = COMMAND_QUEUE + ".retry.1";
  public static final String COMMAND_RETRY_2 = COMMAND_QUEUE + ".retry.2";
  public static final String COMMAND_DLQ = COMMAND_QUEUE + ".dlq";
  public static final String QUERY_RETRY_1 = QUERY_QUEUE + ".retry.1";
  public static final String QUERY_RETRY_2 = QUERY_QUEUE + ".retry.2";
  public static final String QUERY_DLQ = QUERY_QUEUE + ".dlq";
  public static final String EVENT_RETRY_1 = EVENT_QUEUE + ".retry.1";
  public static final String EVENT_RETRY_2 = EVENT_QUEUE + ".retry.2";
  public static final String EVENT_DLQ = EVENT_QUEUE + ".dlq";
  public static final String RESULT_RETRY_1 = RESULT_QUEUE + ".retry.1";
  public static final String RESULT_RETRY_2 = RESULT_QUEUE + ".retry.2";
  public static final String RESULT_DLQ = RESULT_QUEUE + ".dlq";

  private MessagingNames() {}
}
