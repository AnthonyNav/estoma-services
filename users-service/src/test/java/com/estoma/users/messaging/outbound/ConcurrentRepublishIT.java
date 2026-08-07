package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.testsupport.AbstractMessagingIT;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A stale relay worker can legitimately republish the same wire {@code
 * messageId} as a worker that has since taken over the claim (fencing
 * prevents state corruption, not the republish itself —
 * docs/system-contract.md §7, §8). This verifies that two concurrent
 * publications sharing that {@code messageId} do not cross confirms: each
 * gets its own {@code publishAttemptId} internally
 * (unit-tested directly on {@code DefaultConfirmedRabbitPublisher}) and,
 * against a real broker, each completes independently and successfully.
 */
class ConcurrentRepublishIT extends AbstractMessagingIT {

  @Autowired private ConfirmedRabbitPublisher publisher;

  @Test
  void twoConcurrentPublicationsOfTheSameMessageIdBothCompleteIndependently() throws Exception {
    UUID sharedMessageId = UUID.randomUUID();
    String routingKey = "it.no-binding." + UUID.randomUUID();

    Callable<Void> publish =
        () -> {
          publisher.publishEvent(AmqpNames.EXCHANGE_EVENTS, routingKey, wireMessage(sharedMessageId));
          return null;
        };

    ExecutorService executor = Executors.newFixedThreadPool(2);
    try {
      List<Future<Void>> futures = executor.invokeAll(List.of(publish, publish));
      // get() rethrows -- both must have completed without throwing, i.e.
      // both got their own confirm and neither was left hanging or
      // errored out because of the other.
      for (Future<Void> future : futures) {
        future.get(10, TimeUnit.SECONDS);
      }
      assertThat(futures).allSatisfy(future -> assertThat(future.isDone()).isTrue());
    } finally {
      executor.shutdown();
      executor.awaitTermination(5, TimeUnit.SECONDS);
    }
  }

  private static Message wireMessage(UUID messageId) {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setMessageId(messageId.toString());
    return new Message("{}".getBytes(), props);
  }
}
