package com.estoma.authentication.infrastructure.messaging.rpc;

import static org.assertj.core.api.Assertions.assertThat;

import com.estoma.authentication.contracts.AuthenticationRpcOperation;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

class RpcListenerAuthorizationTest {

  @Test
  void rejectsAnInvalidProducer() {
    MessageProperties properties = selfProperties();
    properties.setHeader("x-estoma-producer", "users-service");

    assertThat(RpcListener.isAllowedProducer(AuthenticationRpcOperation.CHANGE_PASSWORD.wireName(), properties)).isFalse();
  }

  @Test
  void rejectsAnInvalidActor() {
    MessageProperties properties = selfProperties();
    properties.setHeader("x-estoma-actor-type", "SYSTEM");

    assertThat(RpcListener.isAllowedProducer(AuthenticationRpcOperation.CHANGE_PASSWORD.wireName(), properties)).isFalse();
  }

  @Test
  void rejectsAnInvalidScope() {
    MessageProperties properties = selfProperties();
    properties.setHeader("x-estoma-actor-scope", "GLOBAL");

    assertThat(RpcListener.isAllowedProducer(AuthenticationRpcOperation.CHANGE_PASSWORD.wireName(), properties)).isFalse();
  }

  @Test
  void permitsRecoveryCodeOnlyForCommunicationsSystemIdentity() {
    MessageProperties properties = new MessageProperties();
    properties.setHeader("x-estoma-producer", "communications");
    properties.setHeader("x-estoma-actor-type", "SYSTEM");
    properties.setHeader("x-estoma-actor-service", "communications");
    properties.setHeader("x-estoma-actor-scope", "GLOBAL");

    assertThat(RpcListener.isAllowedProducer(AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY.wireName(), properties)).isTrue();
    properties.setHeader("x-estoma-producer", "bff");
    assertThat(RpcListener.isAllowedProducer(AuthenticationRpcOperation.GET_RECOVERY_CODE_FOR_DELIVERY.wireName(), properties)).isFalse();
  }

  private static MessageProperties selfProperties() {
    UUID accountId = UUID.randomUUID();
    MessageProperties properties = new MessageProperties();
    properties.setHeader("x-estoma-producer", "bff");
    properties.setHeader("x-estoma-actor-type", "ACCOUNT");
    properties.setHeader("x-estoma-actor-scope", "SELF");
    properties.setHeader("x-estoma-actor-account-id", accountId.toString());
    properties.setHeader("x-estoma-target-account-id", accountId.toString());
    return properties;
  }
}
