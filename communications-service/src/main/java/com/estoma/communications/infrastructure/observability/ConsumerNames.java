package com.estoma.communications.infrastructure.observability;

/**
 * Consumer Inbox identities. Logical names, independent of the physical
 * queue names the messaging tramo (Phase 3) will declare. Unlike {@code
 * authentication-service} (a single {@code EVENTS} consumer),
 * {@code recipient-projection} and {@code appointment-cancelled-adapter}
 * are two conceptually independent consumers from day one (see
 * system_contract.md "Internal Architecture" and execution_plan.md Phase
 * 3, which declares them as separate physical queues).
 */
public final class ConsumerNames {

  public static final String COMMANDS = "communications-service.commands";
  public static final String RECIPIENT_PROJECTION = "communications-service.recipient-projection";
  public static final String APPOINTMENT_CANCELLED_ADAPTER = "communications-service.appointment-cancelled-adapter";

  private ConsumerNames() {}
}
