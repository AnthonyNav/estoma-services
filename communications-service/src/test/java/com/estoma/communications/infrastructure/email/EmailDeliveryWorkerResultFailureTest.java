package com.estoma.communications.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.estoma.communications.application.port.EmailProviderPort;
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
    EmailDelivery delivery = EmailDelivery.generic(UUID.randomUUID(), UUID.randomUUID(), "student@example.test", "Student", "Estoma",
        "no-reply@example.test", null, "subject", "body", null, Instant.now()).claimed(UUID.randomUUID(), Instant.now(), Instant.now().plusSeconds(60), "test");
    EmailProviderPort.EmailSendResult accepted = new EmailProviderPort.EmailSendResult.Accepted("stub", "message-1");
    when(claim.claim(1)).thenReturn(List.of(delivery));
    when(router.select()).thenReturn(new EmailProviderRouter.ProviderSelection("stub", mock(EmailProviderPort.class)));
    when(prepare.prepare(any(EmailDelivery.class), any(String.class))).thenReturn(delivery);
    when(start.start(delivery)).thenReturn(delivery);
    when(router.send(any(), any())).thenReturn(accepted);
    doThrow(new IllegalStateException("database unavailable")).when(result).record(delivery, accepted);
    EmailDeliveryWorker worker = new EmailDeliveryWorker(router, claim, prepare, start, result, templates, preProvider, 1, false);

    assertThatThrownBy(worker::pollAndProcess).isInstanceOf(IllegalStateException.class).hasMessage("database unavailable");

    verify(result).record(delivery, accepted);
  }
}
