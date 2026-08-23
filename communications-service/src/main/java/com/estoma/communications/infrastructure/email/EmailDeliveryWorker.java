package com.estoma.communications.infrastructure.email;

import com.estoma.communications.application.port.EmailProviderPort;
import com.estoma.communications.application.port.PasswordRecoveryDeliveryPort;
import com.estoma.communications.application.port.RecoveryCodeDeliveryPort;
import com.estoma.communications.contracts.EmailContentMode;
import com.estoma.communications.domain.email.EmailDelivery;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Claims email work, releases the DB transaction, calls external dependencies, then records one result transaction. */
@Component
public class EmailDeliveryWorker {
  private static final Logger LOGGER = LoggerFactory.getLogger(EmailDeliveryWorker.class);

  private final EmailProviderRouter provider;
  private final int batchSize;
  private final ClaimEmailDeliveriesTxService claimService;
  private final PrepareEmailDeliveryTxService prepareService;
  private final StartEmailDeliveryTxService startService;
  private final EmailDeliveryResultTxService resultService;
  private final EmailTemplateResolver templateResolver;
  private final PreProviderFailureTxService preProviderFailureService;
  private final ProviderNotStartedRecoveryTxService providerNotStartedRecoveryService;
  private final PasswordRecoveryDeliveryPort recoveryDeliveries;
  private final RecoveryCodeDeliveryPort recoveryCodes;
  private final boolean scheduledEnabled;

  private final AtomicReference<String> lastPollError = new AtomicReference<>();

  EmailDeliveryWorker(
      EmailProviderRouter provider,
      ClaimEmailDeliveriesTxService claimService,
      PrepareEmailDeliveryTxService prepareService,
      StartEmailDeliveryTxService startService,
      EmailDeliveryResultTxService resultService,
      EmailTemplateResolver templateResolver,
      PreProviderFailureTxService preProviderFailureService,
      ProviderNotStartedRecoveryTxService providerNotStartedRecoveryService,
      PasswordRecoveryDeliveryPort recoveryDeliveries,
      RecoveryCodeDeliveryPort recoveryCodes,
      @Value("${estoma.communications.email.worker.batch-size:20}") int batchSize,
      @Value("${estoma.communications.email.worker.enabled:true}") boolean scheduledEnabled) {
    this.provider = provider;
    this.batchSize = batchSize;
    this.claimService = claimService;
    this.prepareService = prepareService;
    this.startService = startService;
    this.resultService = resultService;
    this.templateResolver = templateResolver;
    this.preProviderFailureService = preProviderFailureService;
    this.providerNotStartedRecoveryService = providerNotStartedRecoveryService;
    this.recoveryDeliveries = recoveryDeliveries;
    this.recoveryCodes = recoveryCodes;
    this.scheduledEnabled = scheduledEnabled;
  }

  @Scheduled(fixedDelayString = "${estoma.communications.email.worker.poll-interval-ms:5000}")
  public void scheduledPoll() {
    if (scheduledEnabled) pollAndProcess();
  }

  public void pollAndProcess() {
    try {
      for (int i = 0; i < batchSize; i++) {
        List<EmailDelivery> claimedRows = claimService.claim(1);
        if (claimedRows.isEmpty()) break;
        EmailDelivery claimed = claimedRows.getFirst();
        boolean providerCallStarted = false;
        boolean recordingResult = false;
        try {
          var recovery = recoveryDeliveries.findMetadata(claimed.emailDeliveryId()).orElse(null);
          EmailProviderPort.EmailSendRequest resolved;
          if (recovery != null) {
            Instant beforeRpc = Instant.now();
            if (!recovery.expiresAt().isAfter(beforeRpc)) {
              preProviderFailureService.record(claimed, "RECOVERY_EXPIRED", "recovery expired before provider call");
              continue;
            }

            final String code;
            try {
              code = recoveryCodes.getCode(recovery.recoveryId(), correlationForRecovery(claimed));
            } catch (RecoveryCodeDeliveryPort.NotAvailableException unavailable) {
              preProviderFailureService.record(claimed, unavailable.normalizedCode(), "recovery code is not available");
              continue;
            } catch (RecoveryCodeDeliveryPort.TechnicalException technical) {
              Instant now = Instant.now();
              if (!recovery.expiresAt().isAfter(now)) {
                preProviderFailureService.record(claimed, "RECOVERY_EXPIRED", "recovery expired during Security RPC");
              } else {
                Instant next = now.plusSeconds(5);
                if (next.isAfter(recovery.expiresAt())) next = recovery.expiresAt();
                if (recoveryDeliveries.requeueBeforeProviderAttempt(
                    claimed.emailDeliveryId(), claimed.claimToken(), next, now) != 1) {
                  throw new LostEmailClaimException();
                }
              }
              continue;
            }

            resolved = new EmailProviderPort.EmailSendRequest(
                claimed.recipientAddress(), claimed.recipientName(), claimed.fromName(), claimed.fromAddress(), claimed.replyToAddress(),
                EmailContentMode.GENERIC, "Recuperación de contraseña",
                "Tu código de recuperación es: " + code, null, null, null);
          } else {
            EmailProviderPort.EmailSendRequest request = new EmailProviderPort.EmailSendRequest(
                claimed.recipientAddress(), claimed.recipientName(), claimed.fromName(), claimed.fromAddress(), claimed.replyToAddress(),
                claimed.contentMode(), claimed.subject(), claimed.textBody(), claimed.htmlBody(), claimed.templateCode(), claimed.templateData());
            resolved = templateResolver.resolve(request);
          }

          EmailProviderRouter.ProviderSelection selection = provider.select();
          claimed = prepareService.prepare(claimed, selection.providerCode());
          claimed = startService.start(claimed);
          providerCallStarted = true;
          EmailProviderPort.EmailSendResult result = provider.send(selection, resolved);
          recordingResult = true;
          resultService.record(claimed, result);
        } catch (RuntimeException itemError) {
          LOGGER.warn("email delivery item failed: deliveryId={}, providerCallStarted={}, error={}",
              claimed.emailDeliveryId(), providerCallStarted, itemError.toString());
          if (recordingResult) {
            throw itemError;
          } else if (itemError instanceof EmailProviderRouter.ProviderNotStartedException) {
            providerNotStartedRecoveryService.recover(claimed);
            break;
          } else if (providerCallStarted) {
            throw itemError;
          } else if (itemError instanceof PreProviderValidationException validation) {
            preProviderFailureService.record(claimed, validation.normalizedErrorCode(), validation.sanitizedMessage());
          } else {
            throw itemError;
          }
        }
      }
      lastPollError.set(null);
    } catch (RuntimeException e) {
      lastPollError.set(e.getMessage());
      throw e;
    }
  }

  /** Correlation is non-secret and exists only to match the ephemeral RPC response to this immediate attempt. */
  private static java.util.UUID correlationForRecovery(EmailDelivery claimed) {
    return java.util.UUID.nameUUIDFromBytes(
        (claimed.communicationId().toString() + ":" + claimed.emailDeliveryId()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }

  String lastPollError() { return lastPollError.get(); }
  List<EmailDelivery> claimDue() { return claimService.claim(batchSize); }
  void recordResult(EmailDelivery claimed, EmailProviderPort.EmailSendResult result) { resultService.record(claimed, result); }
}
