package com.estoma.communications.infrastructure.email;

import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.application.port.EmailProviderPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

/** Resolves only local, explicitly registered templates; unknown codes fail before a provider attempt. */
@Component
class EmailTemplateResolver {
  private final Set<String> registeredTemplates;
  EmailTemplateResolver(@Value("${estoma.communications.email.templates:}") String templates) {
    registeredTemplates = Arrays.stream(templates == null ? new String[0] : templates.split(","))
        .map(String::trim).filter(s -> !s.isBlank()).collect(Collectors.toUnmodifiableSet());
  }
  EmailProviderPort.EmailSendRequest resolve(EmailProviderPort.EmailSendRequest request) {
    if (request.contentMode() == EmailContentMode.GENERIC) {
      if (request.subject() == null || request.subject().isBlank() || request.textBody() == null || request.textBody().isBlank())
        throw new IllegalArgumentException("INVALID_FINAL_EMAIL_CONTENT");
      return request;
    }
    if (!registeredTemplates.contains(request.templateCode())) {
      throw new IllegalArgumentException("UNKNOWN_EMAIL_TEMPLATE");
    }
    String data = request.templateData() == null ? "" : request.templateData();
    if (data.isBlank()) throw new IllegalArgumentException("INVALID_TEMPLATE_DATA");
    return new EmailProviderPort.EmailSendRequest(request.recipientAddress(), request.recipientName(), request.fromName(),
        request.fromAddress(), request.replyToAddress(), EmailContentMode.GENERIC, "Local template", data, null, null, null);
  }
}
