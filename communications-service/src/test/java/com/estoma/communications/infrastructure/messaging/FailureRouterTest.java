package com.estoma.communications.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.estoma.communications.contracts.MessagingNames;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class FailureRouterTest {

  private final ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
  private final FailureRouter router = new FailureRouter(publisher);

  @Test
  void transientCommandFailureUsesFirstRetryTier() {
    router.route(message(0), FailureRouter.COMMANDS, new IllegalStateException("temporary"), true);
    verify(publisher).publishRequiredRoute(eq(MessagingNames.DLX_EXCHANGE), eq(MessagingNames.COMMAND_RETRY_5S), any());
  }

  @Test
  void exhaustedCommandFailureUsesDlq() {
    router.route(message(3), FailureRouter.COMMANDS, new IllegalStateException("temporary"), true);
    verify(publisher).publishRequiredRoute(eq(MessagingNames.DLX_EXCHANGE), eq(MessagingNames.COMMAND_DLQ), any());
  }

  @Test
  void permanentEventFailureSkipsRetryAndUsesDlq() {
    router.route(message(0), FailureRouter.EVENTS, new IllegalArgumentException("invalid"), false);
    verify(publisher).publishRequiredRoute(eq(MessagingNames.DLX_EXCHANGE), eq(MessagingNames.EVENT_DLQ), any());
  }

  private static Message message(int attempt) {
    MessageProperties properties = new MessageProperties();
    if (attempt > 0) properties.setHeader(MessagingNames.HEADER_ATTEMPT, attempt);
    return new Message("{}".getBytes(StandardCharsets.UTF_8), properties);
  }
}
