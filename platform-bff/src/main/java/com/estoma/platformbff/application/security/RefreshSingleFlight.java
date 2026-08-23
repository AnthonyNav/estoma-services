package com.estoma.platformbff.application.security;

import java.util.UUID;
import java.util.function.Supplier;

public interface RefreshSingleFlight {
  <T> T execute(UUID sessionId, Supplier<T> operation);
}
