package com.estoma.authentication.contracts;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuthenticationRpcContractTest {

  @Test
  void securityRpcUsesDedicatedExchangeAndDeliveryOperation() {
    assertThat(MessagingNames.SECURITY_RPC_EXCHANGE).isEqualTo("platform.security.rpc");
    assertThat(MessagingNames.AUTHENTICATION_RPC_QUEUE).isEqualTo("rpc.authentication");
    assertThat(AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY.wireName()).isEqualTo("GetRecoveryCodeForDelivery");
  }
}
