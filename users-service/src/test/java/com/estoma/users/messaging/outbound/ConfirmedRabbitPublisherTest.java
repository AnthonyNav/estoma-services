package com.estoma.users.messaging.outbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.estoma.users.config.MessagingProperties;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Verifies {@link DefaultConfirmedRabbitPublisher}'s confirm/return/
 * timeout/interrupt classification in isolation, with a mocked {@link
 * RabbitTemplate} whose {@code send} completes (or fails) the {@link
 * CorrelationData} future the way a real broker round-trip would
 * (docs/system-contract.md §7).
 */
class ConfirmedRabbitPublisherTest {

  private static final Duration SHORT_TIMEOUT = Duration.ofMillis(100);

  private final RabbitTemplate requiredRouteTemplate = mock(RabbitTemplate.class);
  private final RabbitTemplate eventTemplate = mock(RabbitTemplate.class);
  private final MessagingProperties properties = new MessagingProperties(Map.of(), Map.of(), true, 3, 3, SHORT_TIMEOUT);
  private final DefaultConfirmedRabbitPublisher publisher =
      new DefaultConfirmedRabbitPublisher(requiredRouteTemplate, eventTemplate, properties);

  @Test
  void publishRequiredRouteSucceedsOnAck() {
    completeSendWith(requiredRouteTemplate, new CorrelationData.Confirm(true, null), null);

    publisher.publishRequiredRoute("platform.results", "bff", message());
  }

  @Test
  void publishEventSucceedsOnAck() {
    completeSendWith(eventTemplate, new CorrelationData.Confirm(true, null), null);

    publisher.publishEvent("platform.events", "identity.person.created", message());
  }

  @Test
  void nackFailsWithPublishNack() {
    completeSendWith(requiredRouteTemplate, new CorrelationData.Confirm(false, "internal error"), null);

    assertThatThrownBy(() -> publisher.publishRequiredRoute("platform.results", "bff", message()))
        .isInstanceOf(RabbitPublishException.class)
        .satisfies(e -> assertThat(((RabbitPublishException) e).code()).isEqualTo(OutboxFailureCode.PUBLISH_NACK));
  }

  @Test
  void returnedMessageOnRequiredRouteFailsWithPublishReturned() {
    ReturnedMessage returned = new ReturnedMessage(message(), 312, "NO_ROUTE", "platform.results", "unbound-key");
    completeSendWith(requiredRouteTemplate, new CorrelationData.Confirm(true, null), returned);

    assertThatThrownBy(() -> publisher.publishRequiredRoute("platform.results", "unbound-key", message()))
        .isInstanceOf(RabbitPublishException.class)
        .satisfies(
            e -> assertThat(((RabbitPublishException) e).code()).isEqualTo(OutboxFailureCode.PUBLISH_RETURNED));
  }

  @Test
  void returnedMessageOnEventIsIgnoredBecauseRoutingIsNotRequired() {
    ReturnedMessage returned = new ReturnedMessage(message(), 312, "NO_ROUTE", "platform.events", "unbound-key");
    completeSendWith(eventTemplate, new CorrelationData.Confirm(true, null), returned);

    publisher.publishEvent("platform.events", "unbound-key", message());
  }

  @Test
  void timeoutWaitingForConfirmFailsWithPublishTimeout() {
    // send() populates nothing -- the future is never completed, so
    // get(timeout) times out. Using a short configured timeout keeps this
    // test fast without any sleep in the assertion path.
    doAnswer(invocation -> null)
        .when(requiredRouteTemplate)
        .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

    assertThatThrownBy(() -> publisher.publishRequiredRoute("platform.results", "bff", message()))
        .isInstanceOf(RabbitPublishException.class)
        .satisfies(
            e -> assertThat(((RabbitPublishException) e).code()).isEqualTo(OutboxFailureCode.PUBLISH_TIMEOUT));
  }

  @Test
  void amqpExceptionDuringSendFailsWithPublishConnectionFailure() {
    doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
        .when(requiredRouteTemplate)
        .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

    assertThatThrownBy(() -> publisher.publishRequiredRoute("platform.results", "bff", message()))
        .isInstanceOf(RabbitPublishException.class)
        .satisfies(
            e ->
                assertThat(((RabbitPublishException) e).code())
                    .isEqualTo(OutboxFailureCode.PUBLISH_CONNECTION_FAILURE));
  }

  @Test
  void executionExceptionWrappingAConnectionCauseFailsWithPublishConnectionFailure() {
    completeSendExceptionallyWith(requiredRouteTemplate, new AmqpConnectException(new RuntimeException("dropped")));

    assertThatThrownBy(() -> publisher.publishRequiredRoute("platform.results", "bff", message()))
        .isInstanceOf(RabbitPublishException.class)
        .satisfies(
            e ->
                assertThat(((RabbitPublishException) e).code())
                    .isEqualTo(OutboxFailureCode.PUBLISH_CONNECTION_FAILURE));
  }

  @Test
  void executionExceptionWrappingAnUnrelatedCauseFailsWithPublishUnexpectedError() {
    completeSendExceptionallyWith(requiredRouteTemplate, new IllegalStateException("something else entirely"));

    assertThatThrownBy(() -> publisher.publishRequiredRoute("platform.results", "bff", message()))
        .isInstanceOf(RabbitPublishException.class)
        .satisfies(
            e ->
                assertThat(((RabbitPublishException) e).code())
                    .isEqualTo(OutboxFailureCode.PUBLISH_UNEXPECTED_ERROR));
  }

  @Test
  void interruptionRestoresTheFlagAndFailsWithPublishInterrupted() {
    // Future#get checks the interrupt status at entry and throws
    // InterruptedException immediately, clearing the flag as it does --
    // no need for a second thread to actually race an interrupt() call.
    doAnswer(invocation -> null)
        .when(requiredRouteTemplate)
        .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

    Thread.currentThread().interrupt();
    try {
      assertThatThrownBy(() -> publisher.publishRequiredRoute("platform.results", "bff", message()))
          .isInstanceOf(RabbitPublishException.class)
          .satisfies(
              e ->
                  assertThat(((RabbitPublishException) e).code())
                      .isEqualTo(OutboxFailureCode.PUBLISH_INTERRUPTED));

      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted(); // clear, so this doesn't leak into other tests
    }
  }

  @Test
  void everyPublicationGetsItsOwnCorrelationDataId() {
    ArgumentCaptor<CorrelationData> captor = ArgumentCaptor.forClass(CorrelationData.class);
    doAnswer(
            invocation -> {
              CorrelationData data = invocation.getArgument(3);
              data.getFuture().complete(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(requiredRouteTemplate)
        .send(any(String.class), any(String.class), any(Message.class), captor.capture());

    publisher.publishRequiredRoute("platform.results", "bff", message());
    publisher.publishRequiredRoute("platform.results", "bff", message());

    var ids = captor.getAllValues().stream().map(CorrelationData::getId).toList();
    assertThat(ids).doesNotHaveDuplicates();
    // Not the wire messageId -- publishAttemptId is a separate, internal-only identity.
    assertThat(ids).doesNotContain(message().getMessageProperties().getMessageId());
  }

  @Test
  void requiredRouteAndEventUseTheirOwnQualifiedTemplate() {
    completeSendWith(requiredRouteTemplate, new CorrelationData.Confirm(true, null), null);
    completeSendWith(eventTemplate, new CorrelationData.Confirm(true, null), null);

    publisher.publishRequiredRoute("platform.results", "bff", message());
    publisher.publishEvent("platform.events", "identity.person.created", message());

    verify(requiredRouteTemplate)
        .send(eq("platform.results"), eq("bff"), any(Message.class), any(CorrelationData.class));
    verify(eventTemplate)
        .send(eq("platform.events"), eq("identity.person.created"), any(Message.class), any(CorrelationData.class));
  }

  private static void completeSendWith(RabbitTemplate template, CorrelationData.Confirm confirm, ReturnedMessage returned) {
    doAnswer(
            invocation -> {
              CorrelationData data = invocation.getArgument(3);
              if (returned != null) {
                data.setReturned(returned);
              }
              data.getFuture().complete(confirm);
              return null;
            })
        .when(template)
        .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
  }

  private static void completeSendExceptionallyWith(RabbitTemplate template, Throwable cause) {
    doAnswer(
            invocation -> {
              CorrelationData data = invocation.getArgument(3);
              data.getFuture().completeExceptionally(cause);
              return null;
            })
        .when(template)
        .send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));
  }

  private static Message message() {
    MessageProperties props = new MessageProperties();
    props.setMessageId("00000000-0000-0000-0000-000000000001");
    return new Message("{}".getBytes(), props);
  }
}
