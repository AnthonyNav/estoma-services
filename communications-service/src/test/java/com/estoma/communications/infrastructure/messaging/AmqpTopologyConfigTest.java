package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.contracts.MessagingNames;
import org.junit.jupiter.api.Test;
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
}
