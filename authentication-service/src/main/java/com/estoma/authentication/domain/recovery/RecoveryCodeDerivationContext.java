package com.estoma.authentication.domain.recovery;

/** Supported derivation contexts for password recovery codes. */
public final class RecoveryCodeDerivationContext {

  public static final String HMAC_SHA256_V1 = "HMAC_SHA256_V1";

  private RecoveryCodeDerivationContext() {}

  public static boolean isCanonical(String context) {
    return HMAC_SHA256_V1.equals(context);
  }
}
