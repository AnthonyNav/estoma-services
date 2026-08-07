package com.estoma.users.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * {@code estoma.messaging.topology-declare} gates whether this service
 * self-provisions its RabbitMQ topology: {@code true} in dev/test, where
 * nothing else will; {@code false} in staging/prod, where {@code
 * estoma-infra} provisions the same topology via GitOps and the runtime
 * AMQP user has no {@code configure} permission (docs/system-contract.md
 * §9). If the {@code @ConditionalOnProperty} on {@link AmqpTopologyConfig}
 * ever broke, the app would attempt to declare topology in prod and fail
 * to start on an AMQP permission error — this only needs a plain Spring
 * context, not a real broker, since the {@code @Bean} methods just build
 * declarative AMQP objects.
 */
class AmqpTopologyConfigConditionalTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(AmqpTopologyConfig.class);

  @Test
  void topologyIsDeclaredByDefaultWhenThePropertyIsAbsent() {
    contextRunner.run(
        context -> {
          assertThat(context).hasSingleBean(AmqpTopologyConfig.class);
          assertThat(context).hasBean("userServiceCommandTopology");
        });
  }

  @Test
  void topologyIsDeclaredWhenThePropertyIsExplicitlyTrue() {
    contextRunner
        .withPropertyValues("estoma.messaging.topology-declare=true")
        .run(context -> assertThat(context).hasSingleBean(AmqpTopologyConfig.class));
  }

  @Test
  void nothingIsDeclaredWhenThePropertyIsFalse() {
    contextRunner
        .withPropertyValues("estoma.messaging.topology-declare=false")
        .run(context -> assertThat(context).doesNotHaveBean(AmqpTopologyConfig.class));
  }
}
