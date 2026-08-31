package com.estoma.platformbff.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.estoma.platformbff.application.projection.ProjectionGenesisService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ProjectionGenesisStartupTest {
  @Mock ProjectionGenesisService genesis;

  @Test
  void disabledGenesisNeverPromotesAnEmptyDatabase() {
    new ProjectionGenesisStartup(properties(false, false), genesis).run(null);

    verify(genesis, never()).establishFreshBaseline();
  }

  @Test
  void enabledGenesisRequiresTheFreshEnvironmentAttestation() {
    assertThatThrownBy(() -> new ProjectionGenesisStartup(properties(true, false), genesis).run(null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fresh-environment");

    verify(genesis, never()).establishFreshBaseline();
  }

  @Test
  void freshDeploymentExplicitlyStartsTheOneShotBaseline() throws Exception {
    new ProjectionGenesisStartup(properties(true, true), genesis).run(null);

    verify(genesis).establishFreshBaseline();
  }

  private static PlatformProperties properties(boolean enabled, boolean environmentFresh) {
    return new PlatformProperties("bff", null, null, null,
        new PlatformProperties.Genesis(enabled, environmentFresh));
  }
}
