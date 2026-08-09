package com.estoma.communications.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

/** SHA-256 over a fixed-order, null-normalized functional request representation. */
public final class RequestFingerprint {
  private RequestFingerprint() {}
  public static String sha256(String... values) {
    String canonical = String.join("\u001f", java.util.Arrays.stream(values).map(RequestFingerprint::marker).toList());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
  private static String marker(String value) { return value == null ? "<null>" : value.isEmpty() ? "<empty>" : value; }
}
