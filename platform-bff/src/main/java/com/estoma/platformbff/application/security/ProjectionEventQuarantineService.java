package com.estoma.platformbff.application.security;

import com.estoma.platformbff.application.port.QuarantineRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ProjectionEventQuarantineService {
  private static final String CONSUMER_NAME = "bff-projection-events";
  private final QuarantineRepository quarantine;

  public ProjectionEventQuarantineService(QuarantineRepository quarantine) {
    this.quarantine = quarantine;
  }

  public void quarantine(UUID messageId, String reasonCode, byte[] body) {
    try {
      String hash = HexFormat.of().formatHex(
          MessageDigest.getInstance("SHA-256").digest(body));
      quarantine.insert(CONSUMER_NAME, messageId, reasonCode, hash, Instant.now());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }
}
