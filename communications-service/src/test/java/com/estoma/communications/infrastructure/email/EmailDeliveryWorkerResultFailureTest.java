package com.estoma.communications.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.communications.application.port.EmailProviderPort;
import com.estoma.communications.application.port.PasswordRecoveryDeliveryPort;
import com.estoma.communications.application.port.RecoveryCodeDeliveryPort;
import com.estoma.communications.domain.email.EmailDelivery;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EmailDeliveryWorkerResultFailureTest {
  @Test
  void resultTransactionFailurePropagatesWithoutRecordingTheSameProviderResultAgain() {
    EmailProviderRouter router = mock(EmailProviderRouter.class);
    ClaimEmailDeliveriesTxService claim = mock(ClaimEmailDeliveriesTxService.class);
    PrepareEmailDeliveryTxService prepare = mock(PrepareEmailDeliveryTxService.class);
    StartEmailDeliveryTxService start = mock(StartEmailDeliveryTxService.class);
    EmailDeliveryResultTxService result = mock(EmailDeliveryResultTxService.class);
    EmailTemplateResolver templates = mock(EmailTemplateResolver.class);
    PreProviderFailureTxService preProvider = mock(PreProviderFailureTxService.class);
    ProviderNotStartedRecoveryTxService recovery = mock(ProviderNotStartedRecoveryTxService.class);
    PasswordRecoveryDeliveryPort recoveryDeliveries = mock(PasswordRecoveryDeliveryPort.class);
    RecoveryCodeDeliveryPort recoveryCodes = mock(RecoveryCodeDeliveryPort.class);
    EmailDelivery delivery = EmailDelivery.generic(UUID.randomUUID(), UUID.randomUUID(), "student@example.test", "Student", "Estoma",
        "no-reply@example.test", null, "subject", "body", null, Instant.now()).claimed(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(60), "test");
    EmailProviderPort.EmailSendResult accepted = new EmailProviderPort.EmailSendResult.Accepted("stub", "message-1");
    when(claim.claim(1)).thenReturn(List.of(delivery));
    when(templates.resolve(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(router.select()).thenReturn(new EmailProviderRouter.ProviderSelection("stub", mock(EmailProviderPort.class)));
    when(prepare.prepare(any(EmailDelivery.class), any(String.class))).thenReturn(delivery);
    when(start.start(delivery)).thenReturn(delivery);
    when(router.send(any(), any())).thenReturn(accepted);
    doThrow(new IllegalStateException("database unavailable")).when(result).record(delivery, accepted);
    EmailDeliveryWorker worker = worker(router, claim, prepare, start, result, templates, preProvider, recovery, recoveryDeliveries, recoveryCodes);

    assertThatThrownBy(worker::pollAndProcess).isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");
    verify(result).record(delivery, accepted);
  }

  @Test
  void prepareFailurePropagatesWithoutRecordingAPreProviderFailure() {
    EmailProviderRouter router = mock(EmailProviderRouter.class);
    ClaimEmailDeliveriesTxService claim = mock(ClaimEmailDeliveriesTxService.class);
    PrepareEmailDeliveryTxService prepare = mock(PrepareEmailDeliveryTxService.class);
    StartEmailDeliveryTxService start = mock(StartEmailDeliveryTxService.class);
    EmailDeliveryResultTxService result = mock(EmailDeliveryResultTxService.class);
    EmailTemplateResolver templates = mock(EmailTemplateResolver.class);
    PreProviderFailureTxService preProvider = mock(PreProviderFailureTxService.class);
    ProviderNotStartedRecoveryTxService recovery = mock(ProviderNotStartedRecoveryTxService.class);
    PasswordRecoveryDeliveryPort recoveryDeliveries = mock(PasswordRecoveryDeliveryPort.class);
    RecoveryCodeDeliveryPort recoveryCodes = mock(RecoveryCodeDeliveryPort.class);
    EmailDelivery delivery = claimedDelivery();
    when(claim.claim(1)).thenReturn(List.of(delivery));
    when(templates.resolve(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(router.select()).thenReturn(new EmailProviderRouter.ProviderSelection("stub", mock(EmailProviderPort.class)));
    doThrow(new IllegalStateException("database unavailable")).when(prepare).prepare(any(EmailDelivery.class), any(String.class));
    EmailDeliveryWorker worker = worker(router, claim, prepare, start, result, templates, preProvider, recovery, recoveryDeliveries, recoveryCodes);

    assertThatThrownBy(worker::pollAndProcess).isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");
    verify(preProvider, never()).record(any(), any(), any());
    verify(result, never()).record(any(), any());
    verify(router, never()).send(any(), any());
  }

  @Test
  void startFailurePropagatesWithoutRecordingAFakeResult() {
    EmailProviderRouter router = mock(EmailProviderRouter.class);
    ClaimEmailDeliveriesTxService claim = mock(ClaimEmailDeliveriesTxService.class);
    PrepareEmailDeliveryTxService prepare = mock(PrepareEmailDeliveryTxService.class);
    StartEmailDeliveryTxService start = mock(StartEmailDeliveryTxService.class);
    EmailDeliveryResultTxService result = mock(EmailDeliveryResultTxService.class);
    EmailTemplateResolver templates = mock(EmailTemplateResolver.class);
    PreProviderFailureTxService preProvider = mock(PreProviderFailureTxService.class);
    ProviderNotStartedRecoveryTxService recovery = mock(ProviderNotStartedRecoveryTxService.class);
    PasswordRecoveryDeliveryPort recoveryDeliveries = mock(PasswordRecoveryDeliveryPort.class);
    RecoveryCodeDeliveryPort recoveryCodes = mock(RecoveryCodeDeliveryPort.class);
    EmailDelivery delivery = claimedDelivery();
    when(claim.claim(1)).thenReturn(List.of(delivery));
    when(templates.resolve(any())).thenAnswer(invocation -> invocation.getArgument(0));
    when(router.select()).thenReturn(new EmailProviderRouter.ProviderSelection("stub", mock(EmailProviderPort.class)));
    when(prepare.prepare(any(EmailDelivery.class), any(String.class))).thenReturn(delivery);
    doThrow(new IllegalStateException("fencing lost")).when(start).start(delivery);
    EmailDeliveryWorker worker = worker(router, claim, prepare, start, result, templates, preProvider, recovery, recoveryDeliveries, recoveryCodes);

    assertThatThrownBy(worker::pollAndProcess).isInstanceOf(IllegalStateException.class).hasMessage("fencing lost");
    verify(preProvider, never()).record(any(), any(), any());
    verify(result, never()).record(any(), any());
    verify(router, never()).send(any(), any());
  }

  @Test
  void genericValidationFailureIsRecordedBeforePreparingAnAttempt() {
    EmailProviderRouter router = mock(EmailProviderRouter.class);
    ClaimEmailDeliveriesTxService claim = mock(ClaimEmailDeliveriesTxService.class);
    PrepareEmailDeliveryTxService prepare = mock(PrepareEmailDeliveryTxService.class);
    StartEmailDeliveryTxService start = mock(StartEmailDeliveryTxService.class);
    EmailDeliveryResultTxService result = mock(EmailDeliveryResultTxService.class);
    EmailTemplateResolver templates = mock(EmailTemplateResolver.class);
    PreProviderFailureTxService preProvider = mock(PreProviderFailureTxService.class);
    ProviderNotStartedRecoveryTxService recovery = mock(ProviderNotStartedRecoveryTxService.class);
    PasswordRecoveryDeliveryPort recoveryDeliveries = mock(PasswordRecoveryDeliveryPort.class);
    RecoveryCodeDeliveryPort recoveryCodes = mock(RecoveryCodeDeliveryPort.class);
    EmailDelivery delivery = claimedDelivery();
    when(claim.claim(1)).thenReturn(List.of(delivery));
    doThrow(new PreProviderValidationException("INVALID_FINAL_EMAIL_CONTENT", "final email content is invalid"))
        .when(templates).resolve(any());
    EmailDeliveryWorker worker = worker(router, claim, prepare, start, result, templates, preProvider, recovery, recoveryDeliveries, recoveryCodes);

    worker.pollAndProcess();

    verify(preProvider).record(delivery, "INVALID_FINAL_EMAIL_CONTENT", "final email content is invalid");
    verify(prepare, never()).prepare(any(EmailDelivery.class), any(String.class));
    verify(start, never()).start(any());
    verify(router, never()).select();
    verify(router, never()).send(any(), any());
    verify(result, never()).record(any(), any());
  }

  private static EmailDeliveryWorker worker(
      EmailProviderRouter router,
      ClaimEmailDeliveriesTxService claim,
      PrepareEmailDeliveryTxService prepare,
      StartEmailDeliveryTxService start,
      EmailDeliveryResultTxService result,
      EmailTemplateResolver templates,
      PreProviderFailureTxService preProvider,
      ProviderNotStartedRecoveryTxService providerRecovery,
      PasswordRecoveryDeliveryPort recoveryDeliveries,
      RecoveryCodeDeliveryPort recoveryCodes) {
    return new EmailDeliveryWorker(
        router, claim, prepare, start, result, templates, preProvider, providerRecovery,
        recoveryDeliveries, recoveryCodes, 1, false);
  }

  private EmailDelivery claimedDelivery() {
    return EmailDelivery.generic(UUID.randomUUID(), UUID.randomUUID(), "student@example.test", "Student", "Estoma",
        "no-reply@example.test", null, "subject", "body", null, Instant.now())
        .claimed(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(60), "test");
  }
}
