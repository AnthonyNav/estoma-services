package com.estoma.communications.contracts;

/**
 * Exchange and queue names fixed by this service's own technical authority
 * (system_contract.md "Functional Input Interfaces"). The full AMQP
 * topology (DLQ, retry tiers, per-consumer event queues) is added in Phase
 * 3 (execution_plan.md) alongside the live RabbitMQ wiring; only the names
 * that are part of this service's public wire contract are fixed here in
 * Phase 1.
 */
public final class MessagingNames {

  public static final String COMMANDS_EXCHANGE = "platform.commands";
  public static final String RESULTS_EXCHANGE = "platform.results";
  public static final String EVENTS_EXCHANGE = "platform.events";
  public static final String DLX_EXCHANGE = "platform.dlx";

  /**
   * Bare {@code logicalComponentId}, not {@code communications-service}.
   * See system_contract.md "Functional Input Interfaces" for the naming
   * decision and its rationale.
   */
  public static final String COMMAND_QUEUE = "commands.communications";

  private MessagingNames() {}
}
