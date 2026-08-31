package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.contracts.MessagingNames;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;

class AmqpTopologyConfigTest {

  @Test
  void canonicalLogicalComponentNamesRemainStable() {
    assertThat(MessagingNames.COMMAND_QUEUE).isEqualTo("commands.communications");
    assertThat(MessagingNames.EVENT_QUEUE).isEqualTo("events.communications");
    assertThat(MessagingNames.EVENT_RETRY_RETURN).isEqualTo("return.events.communications");
  }

  @Test
  void declaresTheSharedDeadLetterExchangeAsTopic() {
    var exchange = new AmqpTopologyConfig().communicationsDlxExchange();

    assertThat(exchange).isInstanceOf(TopicExchange.class);
    assertThat(exchange.getName()).isEqualTo("platform.dlx");
  }

  @Test
  void declaresOnlyTheAccountEventsConsumedByTheRecipientProjection() {
    AmqpTopologyConfig topology = new AmqpTopologyConfig();
    Queue queue = topology.communicationsEventQueue();
    TopicExchange exchange = topology.communicationsEventsExchange();

    assertThat(accountBindings(topology, queue, exchange))
        .extracting(Binding::getRoutingKey)
        .containsExactlyInAnyOrder(
            "authentication.account.created",
            "authentication.account.activated",
            "authentication.account.deactivated");
  }

  static List<Binding> accountBindings(AmqpTopologyConfig topology, Queue queue, TopicExchange exchange) {
    return List.of(
        topology.communicationsAuthenticationAccountCreatedBinding(queue, exchange),
        topology.communicationsAuthenticationAccountActivatedBinding(queue, exchange),
        topology.communicationsAuthenticationAccountDeactivatedBinding(queue, exchange));
  }
}
