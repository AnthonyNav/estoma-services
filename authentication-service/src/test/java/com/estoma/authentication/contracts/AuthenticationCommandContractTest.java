package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticationCommandContractTest {

  @Test
  void durableCommandsUseTheV1ExchangeAndQueue() {
    assertThat(MessagingNames.COMMANDS_EXCHANGE).isEqualTo("platform.commands");
    assertThat(MessagingNames.AUTHENTICATION_COMMAND_QUEUE).isEqualTo("commands.authentication");
    assertThat(AuthenticationCommand.values()).extracting(AuthenticationCommand::wireName).doesNotContain("SetTemporaryPassword");
  }
}
