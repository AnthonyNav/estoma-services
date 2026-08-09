package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailProviderPort;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Deterministic, no-network implementation of {@link EmailProviderPort}:
 * every send is accepted. Lets {@link EmailDeliveryWorker} and its tests
 * run without a real provider account. Real provider selection and
 * fallback are a pending decision
 * (communications-service/docs/pending_decisions.md), not this class's
 * job.
 */
@Component
class StubEmailProvider implements EmailProviderPort {

  static final String PROVIDER_NAME = "stub";
  private final String mode;

  StubEmailProvider(@Value("${estoma.communications.email.fake.mode:accepted}") String mode) {
    this.mode = mode.toLowerCase();
  }

  @Override
  public EmailSendResult send(EmailSendRequest request) {
    if ("retryable".equals(mode)) return new EmailSendResult.Failed(PROVIDER_NAME, "PROVIDER_RETRYABLE", "provider rejected temporarily", true);
    if ("terminal".equals(mode)) return new EmailSendResult.Failed(PROVIDER_NAME, "PROVIDER_TERMINAL", "provider rejected", false);
    if ("timeout".equals(mode)) {
      try { Thread.sleep(60_000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
      return new EmailSendResult.Indeterminate(PROVIDER_NAME, "PROVIDER_TIMEOUT", "provider call timed out");
    }
    if ("indeterminate".equals(mode)) return new EmailSendResult.Indeterminate(PROVIDER_NAME, "PROVIDER_INDETERMINATE", mode);
    if ("exception".equals(mode)) throw new IllegalStateException("provider exception");
    return new EmailSendResult.Accepted(PROVIDER_NAME, UUID.randomUUID().toString());
  }
}
