package com.estoma.users.messaging;

/**
 * Every exchange, queue, routing key, and header name this service uses.
 * One place to look them up; one place to change them.
 *
 * <p>Exchanges are fixed by estomatologia-platform-docs
 * 02-architecture/messaging-architecture.md §4 (including {@code
 * platform.queries}, added there alongside commands/results/events/dlx,
 * not left open). Routing keys are fixed by this service per
 * docs/system-contract.md §9 (routing keys are explicitly left open by
 * the platform docs — see the companion ADR referenced there).
 */
public final class AmqpNames {

  private AmqpNames() {}

  // ---- Exchanges (platform-wide, fixed) ----
  public static final String EXCHANGE_COMMANDS = "platform.commands";
  public static final String EXCHANGE_RESULTS = "platform.results";
  public static final String EXCHANGE_EVENTS = "platform.events";
  public static final String EXCHANGE_DLX = "platform.dlx";
  public static final String EXCHANGE_QUERIES = "platform.queries";

  // ---- Identity ----
  public static final String SERVICE_NAME = "users-service";
  public static final String BOUNDED_CONTEXT = "identity";

  // ---- Commands: platform.commands -> commands.users-service ----
  public static final String QUEUE_COMMANDS = "commands.users-service";
  public static final String ROUTING_KEY_COMMANDS = SERVICE_NAME;

  // ---- DLQ and retry tiers, all hung off platform.dlx ----
  public static final String QUEUE_COMMANDS_DLQ = "commands.users-service.dlq";
  public static final String ROUTING_KEY_DLQ = "dlq.commands.users-service";

  public static final String QUEUE_RETRY_5S = "commands.users-service.retry.5s";
  public static final String QUEUE_RETRY_30S = "commands.users-service.retry.30s";
  public static final String QUEUE_RETRY_300S = "commands.users-service.retry.300s";

  public static final String ROUTING_KEY_RETRY_5S = "retry.commands.users-service.5s";
  public static final String ROUTING_KEY_RETRY_30S = "retry.commands.users-service.30s";
  public static final String ROUTING_KEY_RETRY_300S = "retry.commands.users-service.300s";

  public static final long RETRY_TTL_5S_MS = 5_000L;
  public static final long RETRY_TTL_30S_MS = 30_000L;
  public static final long RETRY_TTL_300S_MS = 300_000L;

  // ---- Events: identity.person.<eventPastTense> on platform.events (topic) ----
  public static final String ROUTING_KEY_PERSON_CREATED = "identity.person.created";
  public static final String ROUTING_KEY_PERSON_UPDATED = "identity.person.updated";
  public static final String ROUTING_KEY_PERSON_ACTIVATED = "identity.person.activated";
  public static final String ROUTING_KEY_PERSON_DEACTIVATED = "identity.person.deactivated";

  // ---- Queries: platform.queries -> queries.users-service, same routing
  // key namespace as commands (messaging-architecture.md §4) ----
  public static final String QUEUE_QUERIES = "queries.users-service";
  public static final String ROUTING_KEY_QUERIES = SERVICE_NAME;

  // ---- Query DLQ and retry tiers, all hung off platform.dlx -- a
  // gap-filling decision (queries.md doesn't mandate it), added for
  // operational consistency with every other durable queue this service
  // owns (docs/system-contract.md §1's own governance model) ----
  public static final String QUEUE_QUERIES_DLQ = "queries.users-service.dlq";
  public static final String ROUTING_KEY_QUERIES_DLQ = "dlq.queries.users-service";

  public static final String QUEUE_QUERIES_RETRY_5S = "queries.users-service.retry.5s";
  public static final String QUEUE_QUERIES_RETRY_30S = "queries.users-service.retry.30s";
  public static final String QUEUE_QUERIES_RETRY_300S = "queries.users-service.retry.300s";

  public static final String ROUTING_KEY_QUERIES_RETRY_5S = "retry.queries.users-service.5s";
  public static final String ROUTING_KEY_QUERIES_RETRY_30S = "retry.queries.users-service.30s";
  public static final String ROUTING_KEY_QUERIES_RETRY_300S = "retry.queries.users-service.300s";

  // ---- Headers ----
  /** Attempt counter surviving redelivery through the retry tiers; not parsed from x-death. */
  public static final String HEADER_ATTEMPT = "x-estoma-attempt";
}
