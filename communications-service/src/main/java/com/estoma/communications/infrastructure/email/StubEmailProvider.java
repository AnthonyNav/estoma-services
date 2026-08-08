package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailProviderPort;
import java.util.UUID;
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

  @Override
  public EmailSendResult send(EmailSendRequest request) {
    return new EmailSendResult.Accepted(PROVIDER_NAME, UUID.randomUUID().toString());
  }
}
