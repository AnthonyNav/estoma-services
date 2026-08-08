package com.estoma.authentication.infrastructure.observability;

/** Consumer Inbox identities. Logical names, independent of the physical queue names the messaging tramo will declare. */
public final class ConsumerNames {

  public static final String COMMANDS = "authentication-service.commands";
  public static final String EVENTS = "authentication-service.events";

  private ConsumerNames() {}
}
