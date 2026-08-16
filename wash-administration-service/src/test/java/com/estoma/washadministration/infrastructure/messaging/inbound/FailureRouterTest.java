package com.estoma.washadministration.infrastructure.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.washadministration.infrastructure.messaging.AmqpNames;
import com.estoma.washadministration.infrastructure.messaging.outbound.ConfirmedRabbitPublisher;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class FailureRouterTest {

  @Test
  void thirdTransientFailureGoesToDlqInsteadOfMissingRetryQueue() {
    RecordingPublisher publisher = new RecordingPublisher();
    FailureRouter router = new FailureRouter(publisher);
    Message message = new Message(new byte[] {1}, new MessageProperties());
    message.getMessageProperties().setHeader(AmqpNames.HEADER_ATTEMPT, 2);

    FailureRoute route =
        router.routeAfterFailure(message, FailureRouter.COMMAND_TOPOLOGY, new RuntimeException(), true);

    assertThat(route).isEqualTo(FailureRoute.DLQ);
    assertThat(publisher.routingKeys).containsExactly("commands.wash-administration.dlq");
  }

  private static final class RecordingPublisher implements ConfirmedRabbitPublisher {
    private final List<String> routingKeys = new ArrayList<>();

    @Override
    public void publishRequiredRoute(String exchange, String routingKey, Message message) {
      routingKeys.add(routingKey);
    }

    @Override
    public void publishEvent(String exchange, String routingKey, Message message) {}
  }
}
