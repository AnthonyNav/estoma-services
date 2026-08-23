package com.estoma.communications.contracts;

/** Canonical exchange, queue, retry and DLQ names for the Communications logical component. */
public final class MessagingNames {

  public static final String COMMANDS_EXCHANGE = "platform.commands";
  public static final String RESULTS_EXCHANGE = "platform.results";
  public static final String EVENTS_EXCHANGE = "platform.events";
  public static final String DLX_EXCHANGE = "platform.dlx";

  public static final String COMMAND_QUEUE = "commands.communications";
  public static final String EVENT_QUEUE = "events.communications";

  public static final String COMMAND_RETRY_5S = "retry.commands.communications.5s";
  public static final String COMMAND_RETRY_30S = "retry.commands.communications.30s";
  public static final String COMMAND_RETRY_300S = "retry.commands.communications.300s";
  public static final String COMMAND_DLQ = "dlq.commands.communications";

  public static final String EVENT_RETRY_5S = "retry.events.communications.5s";
  public static final String EVENT_RETRY_30S = "retry.events.communications.30s";
  public static final String EVENT_RETRY_300S = "retry.events.communications.300s";
  public static final String EVENT_DLQ = "dlq.events.communications";

  public static final String HEADER_ATTEMPT = "x-estoma-attempt";

  private MessagingNames() {}
}
