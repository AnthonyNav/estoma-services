package com.estoma.communications.infrastructure.email;

import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.application.port.EmailProviderPort;
import org.springframework.stereotype.Component;

/** Resolves only local, explicitly registered templates; unknown codes fail before a provider attempt. */
@Component
class EmailTemplateResolver {
  EmailProviderPort.EmailSendRequest resolve(EmailProviderPort.EmailSendRequest request) {
    if (request.contentMode() == EmailContentMode.GENERIC) {
      if (request.subject() == null || request.subject().isBlank() || request.textBody() == null || request.textBody().isBlank())
        throw new IllegalArgumentException("INVALID_FINAL_EMAIL_CONTENT");
      return request;
    }
    if (!"KNOWN_LOCAL".equals(request.templateCode())) {
      throw new IllegalArgumentException("UNKNOWN_EMAIL_TEMPLATE");
    }
    String data = request.templateData() == null ? "" : request.templateData();
    if (data.isBlank()) throw new IllegalArgumentException("INVALID_TEMPLATE_DATA");
    return new EmailProviderPort.EmailSendRequest(request.recipientAddress(), request.recipientName(), request.fromName(),
        request.fromAddress(), request.replyToAddress(), EmailContentMode.GENERIC, "Local template", data, null, null, null);
  }
}
