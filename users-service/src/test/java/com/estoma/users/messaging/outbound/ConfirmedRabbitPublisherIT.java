package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.testsupport.AbstractMessagingIT;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies {@link DefaultConfirmedRabbitPublisher} against a real broker:
 * the difference between a required-route publication (RESULT/retry/DLQ,
 * must be routable) and an event publication (bindings optional), plus
 * the exchange-missing failure path (docs/system-contract.md §7).
 *
 * <p>Uses random, per-execution routing keys under a reserved {@code
 * it.} prefix rather than relying on "no binding currently exists" on the
 * platform's shared exchanges — nothing in this codebase ever binds
 * anything under that prefix, so these assertions don't depend on test
 * execution order or on other tests' transient bindings having already
 * been torn down.
 */
class ConfirmedRabbitPublisherIT extends AbstractMessagingIT {

  @Autowired private ConfirmedRabbitPublisher publisher;

  @Test
  void requiredRoutePublicationToAnUnboundRoutingKeyIsReportedAsReturned() {
    String unboundRoutingKey = "it.no-binding." + UUID.randomUUID();

    assertThatThrownBy(
            () -> publisher.publishRequiredRoute(AmqpNames.EXCHANGE_RESULTS, unboundRoutingKey, message()))
        .isInstanceOf(RabbitPublishException.class)
        .satisfies(e -> assertThat(((RabbitPublishException) e).code()).isEqualTo(OutboxFailureCode.PUBLISH_RETURNED));

    // The failure must be observed via the returned-message signal, not a
    // timeout -- proving CorrelationData.getReturned() really is
    // populated before the confirm future completes (docs/system-contract.md
    // §7): a test that instead saw PUBLISH_TIMEOUT would indicate the
    // opposite ordering and this whole confirmed-publish design would need
    // an extra wait added after the confirm.
  }

  @Test
  void eventPublicationToAnUnboundRoutingKeySucceedsOnAckAlone() {
    String unboundRoutingKey = "it.no-binding." + UUID.randomUUID();

    // No consumer owns this key -- publishEvent must not fail just
    // because nothing is bound to receive it (docs/system-contract.md §7,
    // §9: EVENT consumers own their own bindings).
    publisher.publishEvent(AmqpNames.EXCHANGE_EVENTS, unboundRoutingKey, message());
  }

  @Test
  void publicationToANonexistentExchangeFails() {
    String nonexistentExchange = "it.nonexistent-exchange." + UUID.randomUUID();

    assertThatThrownBy(() -> publisher.publishRequiredRoute(nonexistentExchange, "any-key", message()))
        .isInstanceOf(RabbitPublishException.class);
  }

  private static Message message() {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setMessageId(UUID.randomUUID().toString());
    return new Message("{}".getBytes(), props);
  }
}
