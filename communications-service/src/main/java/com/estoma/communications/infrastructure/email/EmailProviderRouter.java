package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailProviderPort;
import org.springframework.stereotype.Component;

/** Single-provider router. It deliberately has no fallback or provider SDK dependency. */
@Component
class EmailProviderRouter implements EmailProviderPort {
  private final StubEmailProvider provider;
  EmailProviderRouter(StubEmailProvider provider) { this.provider = provider; }
  @Override public EmailSendResult send(EmailSendRequest request) { return provider.send(request); }
}
