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
import java.util.concurrent.RejectedExecutionException;

/** Single-provider router. It deliberately has no fallback or provider SDK dependency. */
@Component
class EmailProviderRouter {
  private final EmailProviderPort provider;
  private final ThreadPoolTaskExecutor executor;
  private final long timeoutMs;
  EmailProviderRouter(org.springframework.beans.factory.ObjectProvider<EmailProviderPort> providers, StubEmailProvider stub,
      @Value("${estoma.communications.email.worker.provider-timeout-ms:10000}") long timeoutMs,
      @Value("${estoma.communications.email.worker.lease-duration-ms:30000}") long leaseDurationMs,
      @Value("${estoma.communications.email.worker.provider-timeout-safety-margin-ms:1000}") long safetyMarginMs,
      @Qualifier("emailProviderExecutor") ThreadPoolTaskExecutor executor) {
    if (timeoutMs <= 0) throw new IllegalStateException("email provider timeout must be positive");
    if (leaseDurationMs <= timeoutMs + safetyMarginMs) throw new IllegalStateException("email lease duration must exceed provider timeout and safety margin");
    EmailProviderPort selected = providers.getIfUnique();
    this.provider = selected == null ? stub : selected; this.timeoutMs = timeoutMs; this.executor = executor;
  }
  ProviderSelection select() { return new ProviderSelection(StubEmailProvider.PROVIDER_NAME, provider); }

  EmailProviderPort.EmailSendResult send(ProviderSelection selection, EmailProviderPort.EmailSendRequest request) {
    final Future<EmailProviderPort.EmailSendResult> future;
    try {
      future = executor.submit(() -> selection.port().send(request));
    } catch (RejectedExecutionException e) {
      throw new ProviderNotStartedException(e);
    }
    try { return normalize(selection.providerCode(), future.get(timeoutMs, TimeUnit.MILLISECONDS)); }
    catch (TimeoutException e) { future.cancel(true); return new EmailProviderPort.EmailSendResult.Indeterminate(selection.providerCode(), "PROVIDER_TIMEOUT", "provider call timed out"); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); return new EmailProviderPort.EmailSendResult.Indeterminate(selection.providerCode(), "PROVIDER_INTERRUPTED", "provider call interrupted"); }
    catch (ExecutionException e) { return new EmailProviderPort.EmailSendResult.Indeterminate(selection.providerCode(), "PROVIDER_EXCEPTION", "provider call outcome unknown"); }
  }
  private static EmailProviderPort.EmailSendResult normalize(String providerCode, EmailProviderPort.EmailSendResult result) {
    if (result instanceof EmailProviderPort.EmailSendResult.Accepted accepted)
      return new EmailProviderPort.EmailSendResult.Accepted(providerCode, accepted.providerMessageId());
    if (result instanceof EmailProviderPort.EmailSendResult.Failed failed)
      return new EmailProviderPort.EmailSendResult.Failed(providerCode, failed.normalizedErrorCode(), failed.errorMessage(), failed.retryable());
    EmailProviderPort.EmailSendResult.Indeterminate unknown = (EmailProviderPort.EmailSendResult.Indeterminate) result;
    return new EmailProviderPort.EmailSendResult.Indeterminate(providerCode, unknown.normalizedErrorCode(), unknown.errorMessage());
  }
  record ProviderSelection(String providerCode, EmailProviderPort port) {}
  static final class ProviderNotStartedException extends RuntimeException {
    ProviderNotStartedException(Throwable cause) { super(cause); }
  }
}
