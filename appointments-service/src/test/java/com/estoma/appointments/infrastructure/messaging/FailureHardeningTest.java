package com.estoma.appointments.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.estoma.appointments.infrastructure.observability.AppointmentOperationalMetrics;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

class FailureHardeningTest {
  @Test
  void firstTransientFailureRoutesToRetryWithoutRequeueLoop() {
    ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
    AppointmentOperationalMetrics metrics = mock(AppointmentOperationalMetrics.class);
    FailureRouter router = new FailureRouter(publisher, metrics);
    Message message = new Message("{}".getBytes(), new MessageProperties());
    assertThat(router.route(message, "commands.appointments", true)).isEqualTo(FailureRouter.Route.RETRY);
    verify(publisher).publishRequiredRoute(eq("platform.dlx"), eq("commands.appointments.retry.1"), any(Message.class));
    verify(metrics).retryRouted("commands.appointments");
  }

  @Test
  void thirdTransientFailureIsQuarantinedInDlq() {
    ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
    AppointmentOperationalMetrics metrics = mock(AppointmentOperationalMetrics.class);
    FailureRouter router = new FailureRouter(publisher, metrics);
    MessageProperties properties = new MessageProperties();
    properties.setHeader("x-appointments-attempt", 2);
    Message message = new Message(UUID.randomUUID().toString().getBytes(), properties);
    assertThat(router.route(message, "events.appointments", true)).isEqualTo(FailureRouter.Route.DLQ);
    verify(publisher).publishRequiredRoute(eq("platform.dlx"), eq("events.appointments.dlq"), any(Message.class));
    verify(metrics).dlqRouted("events.appointments");
  }

  @Test
  void originalDeliveryIsNackedAndRequeuedWhenRerouteCannotBeConfirmed() throws IOException {
    ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
    AppointmentOperationalMetrics metrics = mock(AppointmentOperationalMetrics.class);
    doThrow(new RabbitPublishException("broker unavailable"))
        .when(publisher).publishRequiredRoute(eq("platform.dlx"), eq("commands.appointments.retry.1"), any(Message.class));
    FailureRouter router = new FailureRouter(publisher, metrics);
    Channel channel = mock(Channel.class);

    DeliveryFailureSupport.routeThenAck(router,
        new Message("{}".getBytes(), new MessageProperties()), "commands.appointments", true, channel, 42L);

    verify(channel).basicNack(42L, false, true);
  }

  @Test
  void originalDeliveryIsAckedOnlyAfterConfirmedReroute() throws IOException {
    ConfirmedRabbitPublisher publisher = mock(ConfirmedRabbitPublisher.class);
    AppointmentOperationalMetrics metrics = mock(AppointmentOperationalMetrics.class);
    FailureRouter router = new FailureRouter(publisher, metrics);
    Channel channel = mock(Channel.class);

    DeliveryFailureSupport.routeThenAck(router,
        new Message("{}".getBytes(), new MessageProperties()), "commands.appointments", true, channel, 43L);

    verify(channel).basicAck(43L, false);
  }
}
