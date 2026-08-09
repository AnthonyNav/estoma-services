package com.estoma.authentication.application.port;

import java.util.UUID;

/** Derives and verifies short-lived recovery codes without persisting them. */
public interface RecoveryCodeDeriver {

  String derive(UUID recoveryId);

  boolean matches(UUID recoveryId, String candidate);
}
