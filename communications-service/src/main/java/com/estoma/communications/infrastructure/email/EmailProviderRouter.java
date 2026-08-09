package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailProviderPort;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Single-provider router. It deliberately has no fallback or provider SDK dependency. */
@Component
class EmailProviderRouter implements EmailProviderPort {
  private final EmailProviderPort provider;
  private final ThreadPoolTaskExecutor executor;
  private final long timeoutMs;
  EmailProviderRouter(org.springframework.beans.factory.ObjectProvider<EmailProviderPort> providers, StubEmailProvider stub,
      @Value("${estoma.communications.email.worker.provider-timeout-ms:10000}") long timeoutMs,
      @Value("${estoma.communications.email.worker.lease-duration-ms:30000}") long leaseDurationMs,
      @Value("${estoma.communications.email.worker.provider-timeout-safety-margin-ms:1000}") long safetyMarginMs,
      @Qualifier("emailProviderExecutor") ThreadPoolTaskExecutor executor) {
    if (leaseDurationMs <= timeoutMs + safetyMarginMs) throw new IllegalStateException("email lease duration must exceed provider timeout and safety margin");
    EmailProviderPort selected = providers.getIfUnique();
    this.provider = selected == null ? stub : selected; this.timeoutMs = timeoutMs; this.executor = executor;
  }
  @Override public EmailSendResult send(EmailSendRequest request) {
    Future<EmailSendResult> future = executor.submit(() -> provider.send(request));
    try { return future.get(timeoutMs, TimeUnit.MILLISECONDS); }
    catch (TimeoutException e) { future.cancel(true); return new EmailSendResult.Indeterminate("unknown", "PROVIDER_TIMEOUT", "provider call timed out"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); return new EmailSendResult.Indeterminate("unknown", "PROVIDER_TIMEOUT", "provider call interrupted"); }
    catch (ExecutionException e) { throw new IllegalStateException(e.getCause()); }
  }
}
