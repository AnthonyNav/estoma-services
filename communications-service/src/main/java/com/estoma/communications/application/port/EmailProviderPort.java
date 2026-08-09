package com.estoma.communications.application.port;

import com.estoma.communications.contracts.EmailContentMode;

/**
 * Neutral send abstraction (interfaces.md §5, README.md §7): no SDK,
 * exception type, or vendor name from any concrete provider ever crosses
 * this boundary. Implementations normalize provider errors before
 * returning.
 */
public interface EmailProviderPort {

  EmailSendResult send(EmailSendRequest request);

  record EmailSendRequest(
      String recipientAddress,
      String recipientName,
      String fromName,
      String fromAddress,
      String replyToAddress,
      EmailContentMode contentMode,
      String subject,
      String textBody,
      String htmlBody,
      String templateCode,
      String templateData) {}

  sealed interface EmailSendResult {

    record Accepted(String provider, String providerMessageId) implements EmailSendResult {}

    record Failed(String provider, String normalizedErrorCode, String errorMessage) implements EmailSendResult {}
  }
}
