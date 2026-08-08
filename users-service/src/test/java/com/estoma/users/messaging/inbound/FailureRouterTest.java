package com.estoma.users.messaging.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.estoma.users.config.MessagingProperties;
import com.estoma.users.messaging.AmqpNames;
import com.estoma.users.messaging.outbound.ConfirmedRabbitPublisher;
import com.estoma.users.messaging.outbound.OutboxFailureCode;
import com.estoma.users.messaging.outbound.RabbitPublishException;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;

/**
 * Verifies {@link FailureRouter}'s retry-tier selection, the DLQ path,
 * and — most importantly — that a republished message is a fresh copy
 * built from an explicit allow-list rather than a mutation of the
 * original or an indiscriminate clone (docs/system-contract.md §7).
 */
class FailureRouterTest {

  private final ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
  private final MessagingProperties properties = new MessagingProperties(Map.of(), true, 3, Duration.ofSeconds(5));
  private final FailureRouter router = new FailureRouter(publisher, properties);

  @Test
  void firstTransientFailureGoesToTheFiveSecondTier() {
    router.routeAfterFailure(originalMessage(), new RuntimeException("boom"), true);

    verify(publisher)
        .publishRequiredRoute(eq(AmqpNames.EXCHANGE_DLX), eq(AmqpNames.ROUTING_KEY_RETRY_5S), any(Message.class));
  }

  @Test
  void secondTransientFailureGoesToTheThirtySecondTier() {
    Message afterOneAttempt = withAttempt(originalMessage(), 1);

    router.routeAfterFailure(afterOneAttempt, new RuntimeException("boom"), true);

    verify(publisher)
        .publishRequiredRoute(eq(AmqpNames.EXCHANGE_DLX), eq(AmqpNames.ROUTING_KEY_RETRY_30S), any(Message.class));
  }

  @Test
  void thirdTransientFailureGoesToTheFiveMinuteTier() {
    Message afterTwoAttempts = withAttempt(originalMessage(), 2);

    router.routeAfterFailure(afterTwoAttempts, new RuntimeException("boom"), true);

    verify(publisher)
        .publishRequiredRoute(eq(AmqpNames.EXCHANGE_DLX), eq(AmqpNames.ROUTING_KEY_RETRY_300S), any(Message.class));
  }

  @Test
  void exhaustedTransientFailureGoesToTheDlq() {
    Message afterThreeAttempts = withAttempt(originalMessage(), 3);

    FailureRoute route = router.routeAfterFailure(afterThreeAttempts, new RuntimeException("boom"), true);

    assertThat(route).isEqualTo(FailureRoute.DLQ);
    verify(publisher)
        .publishRequiredRoute(eq(AmqpNames.EXCHANGE_DLX), eq(AmqpNames.ROUTING_KEY_DLQ), any(Message.class));
  }

  @Test
  void permanentFailureGoesStraightToTheDlqRegardlessOfAttempt() {
    FailureRoute route = router.routeAfterFailure(originalMessage(), new RuntimeException("permanent"), false);

    assertThat(route).isEqualTo(FailureRoute.DLQ);
    verify(publisher)
        .publishRequiredRoute(eq(AmqpNames.EXCHANGE_DLX), eq(AmqpNames.ROUTING_KEY_DLQ), any(Message.class));
  }

  @Test
  void whenTheRepublicationCannotBeConfirmedTheExceptionPropagates() {
    doThrow(new RabbitPublishException(OutboxFailureCode.PUBLISH_NACK, UUID.randomUUID(), null))
        .when(publisher)
        .publishRequiredRoute(any(String.class), any(String.class), any(Message.class));

    assertThatThrownBy(() -> router.routeAfterFailure(originalMessage(), new RuntimeException("boom"), true))
        .isInstanceOf(RabbitPublishException.class);
  }

  @Test
  void theOriginalMessageIsNeverMutated() {
    Message original = messageWithDeliveryMetadata();
    MessageProperties originalProps = original.getMessageProperties();
    String originalConsumerTag = originalProps.getConsumerTag();
    long originalDeliveryTag = originalProps.getDeliveryTag();

    router.routeAfterFailure(original, new RuntimeException("boom"), true);

    assertThat(originalProps.getConsumerTag()).isEqualTo(originalConsumerTag);
    assertThat(originalProps.getDeliveryTag()).isEqualTo(originalDeliveryTag);
    assertThat(originalProps.getHeaders()).doesNotContainKey(AmqpNames.HEADER_ATTEMPT);
  }

  @Test
  void theRepublishedCopyCarriesOnlyTheAllowedPropertiesAndHeaders() {
    Message original = messageWithDeliveryMetadata();

    router.routeAfterFailure(original, new RuntimeException("boom"), true);

    ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
    verify(publisher).publishRequiredRoute(eq(AmqpNames.EXCHANGE_DLX), eq(AmqpNames.ROUTING_KEY_RETRY_5S), captor.capture());
    MessageProperties copy = captor.getValue().getMessageProperties();

    // Allow-listed core properties, carried over.
    assertThat(copy.getContentType()).isEqualTo(original.getMessageProperties().getContentType());
    assertThat(copy.getMessageId()).isEqualTo(original.getMessageProperties().getMessageId());
    assertThat(copy.getCorrelationId()).isEqualTo(original.getMessageProperties().getCorrelationId());
    assertThat(copy.getDeliveryMode()).isEqualTo(original.getMessageProperties().getDeliveryMode());

    // Allow-listed header, carried over.
    assertThat(copy.getHeaders()).containsEntry("traceparent", "00-trace-01");

    // The attempt header reflects THIS attempt, not whatever was on the original.
    assertThat(copy.getHeaders()).containsEntry(AmqpNames.HEADER_ATTEMPT, 1);

    // Previous-delivery metadata must never ride along.
    assertThat(copy.getConsumerTag()).isNull();
    assertThat(copy.getConsumerQueue()).isNull();
    assertThat(copy.getReceivedExchange()).isNull();
    assertThat(copy.getReceivedRoutingKey()).isNull();
    assertThat(copy.getRedelivered()).isNull();
    assertThat(copy.getDeliveryTag()).isZero();
    assertThat(copy.getHeaders()).doesNotContainKeys("x-death", "x-first-death-reason", "x-last-death-reason");
    assertThat(copy.getHeaders()).doesNotContainKey("some-unvetted-header");

    // Body untouched.
    assertThat(captor.getValue().getBody()).isEqualTo(original.getBody());
  }

  private static Message originalMessage() {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setMessageId(UUID.randomUUID().toString());
    return new Message("{}".getBytes(), props);
  }

  private static Message withAttempt(Message original, int attempt) {
    Message copy = new Message(original.getBody(), new MessageProperties());
    copy.getMessageProperties().setContentType(original.getMessageProperties().getContentType());
    copy.getMessageProperties().setMessageId(original.getMessageProperties().getMessageId());
    copy.getMessageProperties().setHeader(AmqpNames.HEADER_ATTEMPT, attempt);
    return copy;
  }

  private static Message messageWithDeliveryMetadata() {
    MessageProperties props = new MessageProperties();
    props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    props.setContentEncoding("UTF-8");
    props.setMessageId(UUID.randomUUID().toString());
    props.setCorrelationId(UUID.randomUUID().toString());
    props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    props.setHeader("traceparent", "00-trace-01");
    props.setHeader("some-unvetted-header", "should-not-survive");
    props.setHeader("x-death", java.util.List.of());
    props.setHeader("x-first-death-reason", "rejected");
    props.setHeader("x-last-death-reason", "rejected");
    // Previous-delivery metadata that must never be republished.
    props.setConsumerTag("consumer-tag-1");
    props.setConsumerQueue("commands.users-service");
    props.setReceivedExchange("platform.commands");
    props.setReceivedRoutingKey("users-service");
    props.setRedelivered(true);
    props.setDeliveryTag(42L);
    return new Message("{\"personId\":\"abc\"}".getBytes(), props);
  }
}
