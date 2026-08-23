package com.estoma.users.messaging;

/** Canonical RabbitMQ names owned or used by users-service. */
public final class AmqpNames {

  private AmqpNames() {}

  public static final String EXCHANGE_COMMANDS = "platform.commands";
  public static final String EXCHANGE_RESULTS = "platform.results";
  public static final String EXCHANGE_EVENTS = "platform.events";
  public static final String EXCHANGE_DLX = "platform.dlx";
  public static final String EXCHANGE_QUERIES = "platform.queries";

  /** Platform logicalComponentId. The deployable remains named users-service. */
  public static final String LOGICAL_COMPONENT_ID = "users";

  public static final String QUEUE_COMMANDS = "commands.users";
  public static final String ROUTING_KEY_COMMANDS = LOGICAL_COMPONENT_ID;
  public static final String QUEUE_COMMANDS_DLQ = "commands.users.dlq";
  public static final String ROUTING_KEY_DLQ = "dlq.commands.users";
  public static final String QUEUE_RETRY_5S = "commands.users.retry.5s";
  public static final String QUEUE_RETRY_30S = "commands.users.retry.30s";
  public static final String QUEUE_RETRY_300S = "commands.users.retry.300s";
  public static final String ROUTING_KEY_RETRY_5S = "retry.commands.users.5s";
  public static final String ROUTING_KEY_RETRY_30S = "retry.commands.users.30s";
  public static final String ROUTING_KEY_RETRY_300S = "retry.commands.users.300s";

  public static final long RETRY_TTL_5S_MS = 5_000L;
  public static final long RETRY_TTL_30S_MS = 30_000L;
  public static final long RETRY_TTL_300S_MS = 300_000L;

  public static final String ROUTING_KEY_PERSON_CREATED = "users.person.created";
  public static final String ROUTING_KEY_PERSON_UPDATED = "users.person.updated";
  public static final String ROUTING_KEY_PERSON_ACTIVATED = "users.person.activated";
  public static final String ROUTING_KEY_PERSON_DEACTIVATED = "users.person.deactivated";

  public static final String QUEUE_QUERIES = "queries.users";
  public static final String ROUTING_KEY_QUERIES = LOGICAL_COMPONENT_ID;
  public static final String QUEUE_QUERIES_DLQ = "queries.users.dlq";
  public static final String ROUTING_KEY_QUERIES_DLQ = "dlq.queries.users";
  public static final String QUEUE_QUERIES_RETRY_5S = "queries.users.retry.5s";
  public static final String QUEUE_QUERIES_RETRY_30S = "queries.users.retry.30s";
  public static final String QUEUE_QUERIES_RETRY_300S = "queries.users.retry.300s";
  public static final String ROUTING_KEY_QUERIES_RETRY_5S = "retry.queries.users.5s";
  public static final String ROUTING_KEY_QUERIES_RETRY_30S = "retry.queries.users.30s";
  public static final String ROUTING_KEY_QUERIES_RETRY_300S = "retry.queries.users.300s";

  public static final String HEADER_ATTEMPT = "x-estoma-attempt";
}
