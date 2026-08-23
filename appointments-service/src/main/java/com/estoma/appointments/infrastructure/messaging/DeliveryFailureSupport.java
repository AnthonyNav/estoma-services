package com.estoma.appointments.infrastructure.messaging;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;

/** ACKs only after a confirmed retry/DLQ reroute; otherwise requeues the original delivery. */
final class DeliveryFailureSupport {
  private DeliveryFailureSupport() {}

  static void routeThenAck(FailureRouter failures, Message message, String queue,
      boolean transientFailure, Channel channel, long deliveryTag) throws IOException {
    try {
      failures.route(message, queue, transientFailure);
      channel.basicAck(deliveryTag, false);
    } catch (RuntimeException routingFailure) {
      channel.basicNack(deliveryTag, false, true);
    }
  }
}
