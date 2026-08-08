package com.estoma.users.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A deterministic, stable digest of a command's functional fields —
 * whatever a retry under a new {@code messageId} but the same {@code
 * idempotencyKey} must still match for {@code PersonCommandDispatcher} to
 * recognize it as the same intention (commands.md §9,
 * messaging-architecture.md §10.1).
 *
 * <p>Delivery/tracing metadata that can legitimately change between a
 * command and its retry — {@code messageId}, {@code operationId}, {@code
 * correlationId}, {@code causationId}, {@code occurredAt} — must never be
 * fed into {@link #of}; only the caller's own functional fields belong
 * here.
 *
 * <p>Not a SHA-256 over ad hoc JSON: property order, whitespace, and
 * {@code null} handling in a JSON serialization can vary without the
 * underlying data changing, which would silently produce a different
 * fingerprint for the same intention. {@link #of} instead builds an
 * explicit canonical string: each field is length-prefixed (so {@code
 * ("ab", "")} can never collide with {@code ("a", "b")}) and {@code null}
 * is encoded with a marker no real value can produce.
 */
public final class RequestFingerprint {

  private static final String NULL_MARKER = "N;";

  private RequestFingerprint() {}

  /** Fields in the exact, fixed order the caller's contract defines — order is part of the fingerprint. */
  public static String of(String... fields) {
    StringBuilder canonical = new StringBuilder();
    for (String field : fields) {
      if (field == null) {
        canonical.append(NULL_MARKER);
      } else {
        canonical.append(field.length()).append(':').append(field).append(';');
      }
    }
    return sha256Hex(canonical.toString());
  }

  private static String sha256Hex(String canonical) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(hash);
    } catch (NoSuchAlgorithmException e) {
      // SHA-256 is a mandatory JDK algorithm (JLS/JCA baseline) -- this
      // can only happen on a broken JVM installation.
      throw new IllegalStateException("SHA-256 MessageDigest not available", e);
    }
  }
}
