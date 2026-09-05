package com.estoma.appointments.contracts;

import java.util.Map;

public final class MessagingNames {
  public static final String LOGICAL_COMPONENT_ID = "appointments";
  public static final String COMMANDS_EXCHANGE = "platform.commands";
  public static final String QUERIES_EXCHANGE = "platform.queries";
  public static final String RESULTS_EXCHANGE = "platform.results";
  public static final String EVENTS_EXCHANGE = "platform.events";
  public static final String DLX_EXCHANGE = "platform.dlx";
  public static final String COMMAND_QUEUE = "commands.appointments";
  public static final String QUERY_QUEUE = "queries.appointments";
  public static final String EVENT_QUEUE = "events.appointments";
  public static final String RESULT_QUEUE = "results.appointments";
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
  public static final Map<String, String> WASH_EXECUTION_EVENT_ROUTES = Map.ofEntries(
      Map.entry("WashArrivalRegistered", "wash-execution.execution.arrival-registered"),
      Map.entry("WashEntryAuthorized", "wash-execution.entry.authorized"),
      Map.entry("WashEntryRejected", "wash-execution.entry.rejected"),
      Map.entry("WashExecutionStarted", "wash-execution.execution.started"),
      Map.entry("WashExecutionCompleted", "wash-execution.execution.completed"),
      Map.entry("WashExecutionCancelled", "wash-execution.execution.cancelled"),
      Map.entry("OperationalResourceDisabled", "wash-execution.operational-resource.disabled"),
      Map.entry("OperationalResourceRestored", "wash-execution.operational-resource.restored"),
      Map.entry("CleaningPeriodStarted", "wash-execution.cleaning-period.started"),
      Map.entry("CleaningPeriodCompleted", "wash-execution.cleaning-period.completed"));
  private MessagingNames() {}
}
