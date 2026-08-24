package com.estoma.communications.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
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
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class PasswordRecoveryEmailWorkerTest {

  @Test
  void codeIsFetchedBeforeAttemptPreparationAndOnlyPassedInMemoryToProvider() {
    Fixture f = new Fixture();
    UUID recoveryId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    when(f.recoveryDeliveries.findMetadata(f.delivery.emailDeliveryId())).thenReturn(Optional.of(
        new PasswordRecoveryDeliveryPort.RecoveryMetadata(
            f.delivery.emailDeliveryId(), f.delivery.communicationId(), recoveryId, Instant.now().plusSeconds(600), correlationId)));
    when(f.recoveryCodes.getCode(recoveryId, correlationId)).thenReturn("123456");
    when(f.router.select()).thenReturn(new EmailProviderRouter.ProviderSelection("stub", mock(EmailProviderPort.class)));
    when(f.prepare.prepare(any(EmailDelivery.class), eq("stub"))).thenReturn(f.delivery);
    when(f.start.start(f.delivery)).thenReturn(f.delivery);
    when(f.router.send(any(), any())).thenReturn(new EmailProviderPort.EmailSendResult.Accepted("stub", "m-1"));

    f.worker().pollAndProcess();

    var order = inOrder(f.recoveryCodes, f.prepare, f.start, f.router);
    order.verify(f.recoveryCodes).getCode(recoveryId, correlationId);
    order.verify(f.prepare).prepare(any(EmailDelivery.class), eq("stub"));
    order.verify(f.start).start(f.delivery);

    ArgumentCaptor<EmailProviderPort.EmailSendRequest> request = ArgumentCaptor.forClass(EmailProviderPort.EmailSendRequest.class);
    verify(f.router).send(any(), request.capture());
    assertThat(request.getValue().textBody()).contains("123456");
    verify(f.templates, never()).resolve(any());
  }

  @Test
  void technicalRpcFailureRequeuesWithoutPreparingAttemptOrCallingProvider() {
    Fixture f = new Fixture();
    UUID recoveryId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    when(f.recoveryDeliveries.findMetadata(f.delivery.emailDeliveryId())).thenReturn(Optional.of(
        new PasswordRecoveryDeliveryPort.RecoveryMetadata(
            f.delivery.emailDeliveryId(), f.delivery.communicationId(), recoveryId, Instant.now().plusSeconds(600), correlationId)));
    when(f.recoveryCodes.getCode(recoveryId, correlationId))
        .thenThrow(new RecoveryCodeDeliveryPort.TechnicalException("timeout"));
    when(f.recoveryDeliveries.requeueBeforeProviderAttempt(eq(f.delivery.emailDeliveryId()), eq(f.delivery.claimToken()), any(), any()))
        .thenReturn(1);

    f.worker().pollAndProcess();

    verify(f.recoveryDeliveries).requeueBeforeProviderAttempt(eq(f.delivery.emailDeliveryId()), eq(f.delivery.claimToken()), any(), any());
    verify(f.prepare, never()).prepare(any(EmailDelivery.class), any(String.class));
    verify(f.start, never()).start(any());
    verify(f.router, never()).select();
    verify(f.router, never()).send(any(), any());
    verify(f.result, never()).record(any(), any());
  }

  @Test
  void unavailableCodeFailsBeforeProviderWithoutAttempt() {
    Fixture f = new Fixture();
    UUID recoveryId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    when(f.recoveryDeliveries.findMetadata(f.delivery.emailDeliveryId())).thenReturn(Optional.of(
        new PasswordRecoveryDeliveryPort.RecoveryMetadata(
            f.delivery.emailDeliveryId(), f.delivery.communicationId(), recoveryId, Instant.now().plusSeconds(600), correlationId)));
    when(f.recoveryCodes.getCode(recoveryId, correlationId))
        .thenThrow(new RecoveryCodeDeliveryPort.NotAvailableException("RECOVERY_NOT_AVAILABLE"));

    f.worker().pollAndProcess();

    verify(f.preProvider).record(f.delivery, "RECOVERY_NOT_AVAILABLE", "recovery code is not available");
    verify(f.prepare, never()).prepare(any(EmailDelivery.class), any(String.class));
    verify(f.router, never()).send(any(), any());
  }

  private static final class Fixture {
    final EmailProviderRouter router = mock(EmailProviderRouter.class);
    final ClaimEmailDeliveriesTxService claim = mock(ClaimEmailDeliveriesTxService.class);
    final PrepareEmailDeliveryTxService prepare = mock(PrepareEmailDeliveryTxService.class);
    final StartEmailDeliveryTxService start = mock(StartEmailDeliveryTxService.class);
    final EmailDeliveryResultTxService result = mock(EmailDeliveryResultTxService.class);
    final EmailTemplateResolver templates = mock(EmailTemplateResolver.class);
    final PreProviderFailureTxService preProvider = mock(PreProviderFailureTxService.class);
    final ProviderNotStartedRecoveryTxService providerRecovery = mock(ProviderNotStartedRecoveryTxService.class);
    final PasswordRecoveryDeliveryPort recoveryDeliveries = mock(PasswordRecoveryDeliveryPort.class);
    final RecoveryCodeDeliveryPort recoveryCodes = mock(RecoveryCodeDeliveryPort.class);
    final EmailDelivery delivery = EmailDelivery.generic(
        UUID.randomUUID(), UUID.randomUUID(), "student@example.test", "Student", "Estoma",
        "no-reply@example.test", null, "placeholder", "placeholder", null, Instant.now())
        .claimed(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(60), "test");

    Fixture() {
      when(claim.claim(1)).thenReturn(List.of(delivery), List.of());
    }

    EmailDeliveryWorker worker() {
      return new EmailDeliveryWorker(
          router, claim, prepare, start, result, templates, preProvider, providerRecovery,
          recoveryDeliveries, recoveryCodes, 1, false);
    }
  }
}
