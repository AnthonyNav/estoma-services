package com.estoma.washexecution.infrastructure.messaging;

public final class AmqpNames {
  public static final long RETRY_1_TTL_MILLIS = 5_000L;
  public static final long RETRY_2_TTL_MILLIS = 30_000L;
  public static final String ATTEMPT_HEADER = "x-estoma-attempt";
  public static final int MAX_ATTEMPTS = 3;

  private AmqpNames() {}
}
