package com.estoma.communications.infrastructure.email;

import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.application.port.EmailProviderPort;
import org.springframework.stereotype.Component;

/** Template rendering is intentionally not implemented; it must fail before an attempt exists. */
@Component
class EmailTemplateResolver {
  EmailProviderPort.EmailSendRequest resolve(EmailProviderPort.EmailSendRequest request) {
    if (request.contentMode() == EmailContentMode.GENERIC) {
      if (request.subject() == null || request.subject().isBlank() || request.textBody() == null || request.textBody().isBlank())
        throw new IllegalArgumentException("INVALID_FINAL_EMAIL_CONTENT");
      return request;
    }
    throw new IllegalArgumentException("EMAIL_TEMPLATE_RENDERING_NOT_CONFIGURED");
  }
}
