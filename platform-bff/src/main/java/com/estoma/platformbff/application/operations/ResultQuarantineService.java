package com.estoma.platformbff.application.operations;

import com.estoma.platformbff.application.port.QuarantineRepository;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ResultQuarantineService {
  private static final String CONSUMER_NAME = "bff-command-results";
  private final QuarantineRepository quarantine;

  public ResultQuarantineService(QuarantineRepository quarantine) {
    this.quarantine = quarantine;
  }

  public void quarantine(UUID messageId, String reasonCode, byte[] body) {
    try {
      var digest = MessageDigest.getInstance("SHA-256");
      String hash = HexFormat.of().formatHex(digest.digest(body));
      quarantine.insert(CONSUMER_NAME, messageId, reasonCode, hash, Instant.now());
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 is required", impossible);
    }
  }
}
