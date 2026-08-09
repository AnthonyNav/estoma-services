package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailProviderPort;
import com.estoma.communications.domain.email.EmailDelivery;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Implements "Email Delivery Operational Policy" (system_contract.md):
 * claims due deliveries in one transaction, calls the provider with NO
 * transaction open, then records the outcome in a second transaction --
 * data-consistency-architecture.md's hard rule that an external call must
 * never happen inside an open database transaction.
 *
 * <p>Provider retry policy is six physical calls per cycle with gaps {@code
 * 1m, 5m, 30m, 2h, 24h}; the sixth failure is terminal.
 */
@Component
public class EmailDeliveryWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(EmailDeliveryWorker.class);

  private final ObjectProvider<EmailProviderPort> providers;
  private final int batchSize;
  private final ClaimEmailDeliveriesTxService claimService;
  private final PrepareEmailDeliveryTxService prepareService;
  private final StartEmailDeliveryTxService startService;
  private final EmailDeliveryResultTxService resultService;
  private final EmailTemplateResolver templateResolver;
  private final PreProviderFailureTxService preProviderFailureService;
  private final boolean scheduledEnabled;

  private final AtomicReference<String> lastPollError = new AtomicReference<>();

  EmailDeliveryWorker(
      ObjectProvider<EmailProviderPort> providers,
      ClaimEmailDeliveriesTxService claimService,
      PrepareEmailDeliveryTxService prepareService,
      StartEmailDeliveryTxService startService,
      EmailDeliveryResultTxService resultService,
      EmailTemplateResolver templateResolver,
      PreProviderFailureTxService preProviderFailureService,
      @Value("${estoma.communications.email.worker.batch-size:20}") int batchSize,
      @Value("${estoma.communications.email.worker.enabled:true}") boolean scheduledEnabled) {
    this.providers = providers;
    this.batchSize = batchSize;
    this.claimService = claimService;
    this.prepareService = prepareService;
    this.startService = startService;
    this.resultService = resultService;
    this.templateResolver = templateResolver;
    this.preProviderFailureService = preProviderFailureService;
    this.scheduledEnabled = scheduledEnabled;
  }

  @Scheduled(fixedDelayString = "${estoma.communications.email.worker.poll-interval-ms:5000}")
  public void scheduledPoll() {
    if (scheduledEnabled) pollAndProcess();
  }

  public void pollAndProcess() {
    try {
      for (EmailDelivery claimed : claimService.claim(batchSize)) {
        boolean providerCallStarted = false;
        try {
          EmailProviderPort.EmailSendRequest request = new EmailProviderPort.EmailSendRequest(
              claimed.recipientAddress(), claimed.recipientName(), claimed.fromName(), claimed.fromAddress(), claimed.replyToAddress(),
              claimed.contentMode(), claimed.subject(), claimed.textBody(), claimed.htmlBody(), claimed.templateCode(), claimed.templateData());
          EmailProviderPort.EmailSendRequest resolved = templateResolver.resolve(request);
          claimed = prepareService.prepare(claimed);
          claimed = startService.start(claimed);
          providerCallStarted = true;
          // No transaction is open here: this is the only place the provider is called.
          EmailProviderPort.EmailSendResult result = provider().send(resolved);
          resultService.record(claimed, result);
        } catch (RuntimeException itemError) {
          LOGGER.warn("email delivery item failed: deliveryId={}, providerCallStarted={}, error={}",
              claimed.emailDeliveryId(), providerCallStarted, itemError.toString());
          if (providerCallStarted) {
            resultService.record(claimed,
                new EmailProviderPort.EmailSendResult.Indeterminate("stub", "PROVIDER_EXCEPTION", "provider call outcome unknown"));
          } else {
            preProviderFailureService.record(claimed, "PRE_PROVIDER_VALIDATION", "delivery could not be prepared");
          }
        }
      }
      lastPollError.set(null);
    } catch (RuntimeException e) {
      lastPollError.set(e.getMessage());
      throw e;
    }
  }

  private EmailProviderPort provider() {
    EmailProviderPort selected = providers.getIfUnique();
    return selected != null ? selected : providers.stream().filter(p -> p instanceof EmailProviderRouter).findFirst().orElseThrow();
  }

  /**
   * Read by {@link EmailWorkerHealthIndicator}. A method, not a field: this
   * bean is proxied for {@code @Transactional}, and direct field access
   * from another bean would silently read the (uninitialized) proxy's own
   * copy of the field rather than delegating to the real target.
   */
  String lastPollError() {
    return lastPollError.get();
  }

  List<EmailDelivery> claimDue() { return claimService.claim(batchSize); }

  void recordResult(EmailDelivery claimed, EmailProviderPort.EmailSendResult result) { resultService.record(claimed, result); }

}
