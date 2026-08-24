package com.estoma.communications.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.communications.contracts.MessagingNames;
import org.junit.jupiter.api.Test;

class AmqpTopologyConfigTest {

  @Test
  void canonicalLogicalComponentNamesRemainStable() {
    assertThat(MessagingNames.COMMAND_QUEUE).isEqualTo("commands.communications");
    assertThat(MessagingNames.EVENT_QUEUE).isEqualTo("events.communications");
    assertThat(MessagingNames.EVENT_RETRY_RETURN).isEqualTo("return.events.communications");
  }
}
