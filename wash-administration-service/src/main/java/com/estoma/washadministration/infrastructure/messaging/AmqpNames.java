package com.estoma.washadministration.infrastructure.messaging;

public final class AmqpNames {

  public static final long RETRY_1_TTL_MILLIS = 5_000L;
  public static final long RETRY_2_TTL_MILLIS = 30_000L;
  public static final String HEADER_ATTEMPT = "x-estoma-attempt";

  private AmqpNames() {}
}
