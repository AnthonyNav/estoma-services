package com.estoma.authentication.application.port;

import java.util.UUID;

/** Derives and verifies short-lived recovery codes without persisting them. */
public interface RecoveryCodeDeriver {

  String derive(UUID recoveryId, String codeDerivationContext);

  boolean matches(UUID recoveryId, String codeDerivationContext, String candidate);
}
