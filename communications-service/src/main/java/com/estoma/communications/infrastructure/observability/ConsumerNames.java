package com.estoma.communications.infrastructure.observability;

/** Stable Consumer Inbox identities independent of physical queue names. */
public final class ConsumerNames {

  public static final String COMMANDS = "communications-service.commands";
  public static final String RECIPIENT_PROJECTION = "communications-service.recipient-projection";
  public static final String APPOINTMENT_CANCELLED_ADAPTER = "communications-service.appointment-cancelled-adapter";
  public static final String PASSWORD_RECOVERY_ADAPTER = "communications-service.password-recovery-adapter";

  private ConsumerNames() {}
}
