package com.estoma.appointments.infrastructure.security;

import com.estoma.appointments.application.port.out.AppointmentQrRepresentationIssuer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** QR is an integrity-protected appointment reference, never an entry credential. */
@Component
public final class AppointmentQrTokenService implements AppointmentQrRepresentationIssuer {
  static final int MINIMUM_SECRET_BYTES = 32;
  private final boolean enabled;
  private final byte[] secret;

  @Autowired
  public AppointmentQrTokenService(@Value("${estoma.qr.enabled:false}") boolean enabled,
      @Value("${estoma.qr.secret:}") String secret) {
    this.enabled = enabled;
    this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    if (enabled && (secret == null || secret.isBlank() || this.secret.length < MINIMUM_SECRET_BYTES)) {
      throw new IllegalStateException(
          "QR emission requires a non-blank secret of at least " + MINIMUM_SECRET_BYTES + " bytes");
    }
  }

  /** Test/standalone constructor; production activation is controlled by estoma.qr.enabled. */
  public AppointmentQrTokenService(String secret) {
    this(true, secret);
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public String issue(UUID appointmentId) {
    if (!enabled) throw new IllegalStateException("QR emission is disabled");
    String value = appointmentId.toString();
    return "v1." + value + "." + sign(value);
  }
  public UUID verify(String token) {
    if (secret.length == 0 || token == null) throw new IllegalArgumentException("QR token cannot be verified");
    String[] parts = token.split("\\.", -1);
    if (parts.length != 3 || !"v1".equals(parts[0])) throw new IllegalArgumentException("invalid QR token");
    UUID id;
    try { id = UUID.fromString(parts[1]); } catch (IllegalArgumentException exception) { throw new IllegalArgumentException("invalid QR appointment id", exception); }
    if (!MessageDigest.isEqual(parts[2].getBytes(StandardCharsets.US_ASCII), sign(parts[1]).getBytes(StandardCharsets.US_ASCII))) throw new IllegalArgumentException("invalid QR signature");
    return id;
  }
  private String sign(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(secret, "HmacSHA256"));
      return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (GeneralSecurityException exception) { throw new IllegalStateException("HMAC unavailable", exception); }
  }
}
