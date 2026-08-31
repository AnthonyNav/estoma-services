package com.estoma.authentication.infrastructure.messaging;

public final class AmqpNames {

  private AmqpNames() {}

  public static final String EXCHANGE_DLX = "platform.dlx";

  public static final String QUEUE_COMMANDS_DLQ = "commands.authentication.dlq";
  public static final String ROUTING_KEY_COMMANDS_DLQ = "dlq.commands.authentication";
  public static final String QUEUE_COMMANDS_RETRY_5S = "commands.authentication.retry.5s";
  public static final String QUEUE_COMMANDS_RETRY_30S = "commands.authentication.retry.30s";
  public static final String QUEUE_COMMANDS_RETRY_300S = "commands.authentication.retry.300s";
  public static final String ROUTING_KEY_COMMANDS_RETRY_5S = "retry.commands.authentication.5s";
  public static final String ROUTING_KEY_COMMANDS_RETRY_30S = "retry.commands.authentication.30s";
  public static final String ROUTING_KEY_COMMANDS_RETRY_300S = "retry.commands.authentication.300s";

  public static final String QUEUE_QUERIES_DLQ = "queries.authentication.dlq";
  public static final String ROUTING_KEY_QUERIES_DLQ = "dlq.queries.authentication";
  public static final String QUEUE_QUERIES_RETRY_5S = "queries.authentication.retry.5s";
  public static final String QUEUE_QUERIES_RETRY_30S = "queries.authentication.retry.30s";
  public static final String QUEUE_QUERIES_RETRY_300S = "queries.authentication.retry.300s";
  public static final String ROUTING_KEY_QUERIES_RETRY_5S = "retry.queries.authentication.5s";
  public static final String ROUTING_KEY_QUERIES_RETRY_30S = "retry.queries.authentication.30s";
  public static final String ROUTING_KEY_QUERIES_RETRY_300S = "retry.queries.authentication.300s";

  public static final long RETRY_TTL_5S_MS = 5_000L;
  public static final long RETRY_TTL_30S_MS = 30_000L;
  public static final long RETRY_TTL_300S_MS = 300_000L;

  public static final String QUEUE_EVENTS = "events.authentication-service";
  public static final String QUEUE_EVENTS_DLQ = "events.authentication-service.dlq";
  public static final String ROUTING_KEY_EVENTS_DLQ = "dlq.events.authentication-service";
  public static final String QUEUE_EVENTS_RETRY_5S = "events.authentication-service.retry.5s";
  public static final String ROUTING_KEY_EVENTS_RETRY_5S = "retry.events.authentication-service.5s";

  public static final String ROUTING_KEY_RESULTS_BFF = "bff";
  public static final String ROUTING_KEY_RESULTS_ACADEMIC = "academic";

  public static final String ROUTING_KEY_PERSON_CREATED = "users.person.created";
  public static final String ROUTING_KEY_PERSON_ACTIVATED = "users.person.activated";
  public static final String ROUTING_KEY_PERSON_DEACTIVATED = "users.person.deactivated";
  public static final String ROUTING_KEY_STUDENT_NUMBER_CORRECTED = "academic.student.number-corrected";

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

  public static final long RPC_MESSAGE_TTL_MS = 10_000L;
  public static final String HEADER_ATTEMPT = "x-estoma-attempt";
  public static final String PRODUCER = "authentication";
}
