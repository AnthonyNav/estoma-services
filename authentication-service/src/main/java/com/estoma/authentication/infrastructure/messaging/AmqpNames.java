package com.estoma.authentication.infrastructure.messaging;

/**
 * Physical RabbitMQ names this service declares and binds to, beyond the
 * four exchanges and two queue names already fixed as adapter-facing
 * vocabulary in {@link com.estoma.authentication.contracts.MessagingNames}.
 *
 * <p>Event routing keys under {@code identity.person.*} match what {@code
 * users-service} actually publishes ({@code BOUNDED_CONTEXT = "identity"}
 * in its own {@code AmqpNames}) rather than the {@code users.person.*}
 * example string in messaging-architecture.md §4 -- the doc's example is
 * illustrative, not authoritative over the real producer.
 * {@code academic.student.number-corrected} has no real producer yet
 * ({@code academic-service} implements no publisher): it follows the
 * conceptual routing-key pattern from the same section and is a Phase-3
 * reconciliation point (docs/pending_decisions.md).
 */
public final class AmqpNames {

  private AmqpNames() {}

  public static final String EXCHANGE_DLX = "platform.dlx";

  // ---- Commands: commands.authentication, its DLQ, and 3 retry tiers ----
  public static final String QUEUE_COMMANDS_DLQ = "commands.authentication.dlq";
  public static final String ROUTING_KEY_COMMANDS_DLQ = "dlq.commands.authentication";

  public static final String QUEUE_COMMANDS_RETRY_5S = "commands.authentication.retry.5s";
  public static final String QUEUE_COMMANDS_RETRY_30S = "commands.authentication.retry.30s";
  public static final String QUEUE_COMMANDS_RETRY_300S = "commands.authentication.retry.300s";

  public static final String ROUTING_KEY_COMMANDS_RETRY_5S = "retry.commands.authentication.5s";
  public static final String ROUTING_KEY_COMMANDS_RETRY_30S = "retry.commands.authentication.30s";
  public static final String ROUTING_KEY_COMMANDS_RETRY_300S = "retry.commands.authentication.300s";

  public static final long RETRY_TTL_5S_MS = 5_000L;
  public static final long RETRY_TTL_30S_MS = 30_000L;
  public static final long RETRY_TTL_300S_MS = 300_000L;

  // ---- Events: events.authentication-service, its DLQ, and 1 retry tier ----
  public static final String QUEUE_EVENTS = "events.authentication-service";
  public static final String QUEUE_EVENTS_DLQ = "events.authentication-service.dlq";
  public static final String ROUTING_KEY_EVENTS_DLQ = "dlq.events.authentication-service";
  public static final String QUEUE_EVENTS_RETRY_5S = "events.authentication-service.retry.5s";
  public static final String ROUTING_KEY_EVENTS_RETRY_5S = "retry.events.authentication-service.5s";

  public static final String ROUTING_KEY_PERSON_CREATED = "identity.person.created";
  public static final String ROUTING_KEY_PERSON_ACTIVATED = "identity.person.activated";
  public static final String ROUTING_KEY_PERSON_DEACTIVATED = "identity.person.deactivated";
  public static final String ROUTING_KEY_STUDENT_NUMBER_CORRECTED = "academic.student.number-corrected";

  // ---- Events published by this service (platform.events, topic) ----
  public static final String ROUTING_KEY_ACCOUNT_CREATED = "authentication.account.created";
  public static final String ROUTING_KEY_ACCOUNT_ACTIVATED = "authentication.account.activated";
  public static final String ROUTING_KEY_ACCOUNT_DEACTIVATED = "authentication.account.deactivated";
  public static final String ROUTING_KEY_LOGIN_IDENTIFIER_CHANGED = "authentication.account.login-identifier-changed";
  public static final String ROUTING_KEY_STUDENT_LOGIN_SYNC_FAILED =
      "authentication.account.student-login-identifier-synchronization-failed";
  public static final String ROUTING_KEY_PASSWORD_CHANGED = "authentication.credential.password-changed";
  public static final String ROUTING_KEY_TEMPORARY_PASSWORD_SET = "authentication.credential.temporary-password-set";
  public static final String ROUTING_KEY_TEMPORARY_PASSWORD_REGENERATED =
      "authentication.credential.temporary-password-regenerated";
  public static final String ROUTING_KEY_SESSION_REVOKED = "authentication.session.revoked";
  public static final String ROUTING_KEY_ALL_ACCOUNT_SESSIONS_REVOKED = "authentication.session.all-revoked";
  public static final String ROUTING_KEY_OTHER_ACCOUNT_SESSIONS_REVOKED = "authentication.session.other-revoked";
  public static final String ROUTING_KEY_PASSWORD_RECOVERY_REQUESTED = "authentication.password-recovery.requested";

  public static final String ROUTING_KEY_RESULTS_BFF = "results.bff";
  public static final String ROUTING_KEY_RESULTS_STUDENT_IMPORT_PROCESS = "results.student-import-process";

  /** RPC Ephemeral Operational Parameters (system_contract.md): message TTL 5s, no DLQ, no retry tiers. */
  public static final long RPC_MESSAGE_TTL_MS = 5_000L;

  /** Attempt counter surviving redelivery through the retry tiers; not parsed from x-death. */
  public static final String HEADER_ATTEMPT = "x-estoma-attempt";

  /** This service's {@code logicalComponentId} (messaging-architecture.md §4) -- every RESULT/EVENT it publishes carries this as {@code producer}. */
  public static final String PRODUCER = "authentication";
}
