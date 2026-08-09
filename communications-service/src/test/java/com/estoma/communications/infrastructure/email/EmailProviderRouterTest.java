package com.estoma.communications.infrastructure.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.estoma.communications.application.port.EmailProviderPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class EmailProviderRouterTest {
  @Test
  void timeoutIsRecordedAsAnIndeterminateResultForTheSelectedProvider() {
    EmailProviderPort slowProvider = request -> {
      try {
        Thread.sleep(1_000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
      return new EmailProviderPort.EmailSendResult.Accepted("ignored", "late");
    };
    @SuppressWarnings("unchecked")
    ObjectProvider<EmailProviderPort> providers = mock(ObjectProvider.class);
    when(providers.getIfUnique()).thenReturn(slowProvider);
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(1);
    executor.setMaxPoolSize(1);
    executor.initialize();
    try {
      EmailProviderRouter router = new EmailProviderRouter(providers, new StubEmailProvider("accepted"), 10, 100, 10, executor);

      var result = router.send(router.select(), new EmailProviderPort.EmailSendRequest("student@example.test", "Student", "Estoma",
          "no-reply@example.test", null, com.estoma.communications.contracts.EmailContentMode.GENERIC, "subject", "body", null, null, null));

      assertThat(result).isInstanceOf(EmailProviderPort.EmailSendResult.Indeterminate.class);
      EmailProviderPort.EmailSendResult.Indeterminate timeout = (EmailProviderPort.EmailSendResult.Indeterminate) result;
      assertThat(timeout.provider()).isEqualTo("stub");
      assertThat(timeout.normalizedErrorCode()).isEqualTo("PROVIDER_TIMEOUT");
    } finally {
      executor.shutdown();
    }
  }
}
