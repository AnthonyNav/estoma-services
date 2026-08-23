package com.estoma.washexecution.contracts;

import java.util.List;

public final class MessagingNames {
  public static final String LOGICAL_COMPONENT_ID = "wash-execution";

  public static final String COMMANDS_EXCHANGE = "platform.commands";
  public static final String RESULTS_EXCHANGE = "platform.results";
  public static final String EVENTS_EXCHANGE = "platform.events";
  public static final String QUERIES_EXCHANGE = "platform.queries";
  public static final String DLX_EXCHANGE = "platform.dlx";

  public static final String COMMAND_QUEUE = "commands.wash-execution";
  public static final String QUERY_QUEUE = "queries.wash-execution";
  public static final String EVENT_QUEUE = "events.wash-execution";
  public static final String RESULT_QUEUE = "results.wash-execution";

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

  public static final List<String> EVENT_BINDINGS = List.of(
      "appointments.appointment-time-slot.created",
      "appointments.appointment-time-slot.cancelled",
      "appointments.appointment.scheduled",
      "appointments.appointment.cancelled",
      "appointments.appointment.missed",
      "wash-administration.administrative-closure.scheduled",
      "wash-administration.administrative-closure.cancelled",
      "wash-administration.cabin.registered",
      "wash-administration.cabin.updated",
      "wash-administration.cabin.activated",
      "wash-administration.cabin.deactivated",
      "wash-administration.cabin.retired",
      "wash-administration.tank.registered",
      "wash-administration.tank.updated",
      "wash-administration.tank.activated",
      "wash-administration.tank.deactivated",
      "wash-administration.tank.retired",
      "wash-administration.resource-priority.configured",
      "authorization.account-role.assigned",
      "authorization.account-role.changed",
      "authorization.system-access.granted",
      "authorization.system-access.suspended",
      "authorization.system-access.revoked",
      "authorization.system-access.restored");

  private MessagingNames() {}
}
