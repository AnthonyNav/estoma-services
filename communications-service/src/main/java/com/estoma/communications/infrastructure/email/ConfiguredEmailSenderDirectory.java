package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailSenderDirectory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * V1 resolution: every {@code senderCode} maps to the single configured
 * platform sender, regardless of its value. The definitive {@code
 * senderCode} catalog is a pending decision
 * (communications-service/docs/pending_decisions.md); this keeps the wire
 * contract already neutral (README.md §7) so no caller needs to change
 * once a real catalog exists.
 */
@Component
class ConfiguredEmailSenderDirectory implements EmailSenderDirectory {

  private final String defaultFromName;
  private final String defaultFromAddress;

  ConfiguredEmailSenderDirectory(
      @Value("${estoma.communications.email.default-sender-name}") String defaultFromName,
      @Value("${estoma.communications.email.default-sender-address}") String defaultFromAddress) {
    this.defaultFromName = defaultFromName;
    this.defaultFromAddress = defaultFromAddress;
  }

  @Override
  public ResolvedSender resolve(String senderCode) {
    return new ResolvedSender(defaultFromName, defaultFromAddress);
  }
}
